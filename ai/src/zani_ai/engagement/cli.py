from __future__ import annotations

import argparse
import sys
from collections.abc import Callable, Sequence
from pathlib import Path

from zani_ai.engagement.contracts import (
    CLASS_WEIGHTING_SCHEMES,
    DatasetContract,
    DatasetContractError,
    load_dataset_contract,
)
from zani_ai.engagement.locking import DirectoryLock, LockUnavailableError
from zani_ai.engagement.publish import (
    DEFAULT_RESULTS_BRANCH,
    LOCK_FILENAME,
    default_source_label,
    publish_once,
    require_results_branch,
    run_forever,
)
from zani_ai.engagement.runtime import parse_device

type Command = Callable[[argparse.Namespace], int]


def _device(value: str) -> str:
    """argparse type for ``--device``: ``cpu``, ``cuda`` or ``cuda:N``.

    A plain ``choices`` list cannot express the index, and on a shared server
    only one card is usually allocated.
    """
    try:
        parse_device(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(str(error)) from error
    return value


def _sample_fps(value: str) -> float:
    """argparse type for ``--sample-fps``: a positive frame rate.

    10 keeps the original ``raw_frames_v1`` cache; 30 matches the source videos
    and the paper, and writes its own ``raw_frames_30fps_v1`` cache.
    """
    try:
        rate = float(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(f"sample-fps must be a number, got {value!r}") from error
    if not 0 < rate <= 120:
        raise argparse.ArgumentTypeError(f"sample-fps must be in (0, 120], got {rate}")
    return rate


def _source(value: str) -> str:
    """argparse type for ``--source``: exactly one path segment.

    The label is the top-level directory of the snapshot inside the branch. A
    separator would nest it where nobody looks for it, ``.`` would stage every
    other box's directory, and ``..`` writes outside the worktree before failing
    on the ``git add`` pathspec.
    """
    if not value:
        raise argparse.ArgumentTypeError("source must not be empty")
    if "/" in value or "\\" in value:
        raise argparse.ArgumentTypeError(
            f"source must be one path segment without separators, got {value!r}"
        )
    if value in {".", ".."}:
        raise argparse.ArgumentTypeError(f"source must name a directory, got {value!r}")
    return value


def _interval(value: str) -> float:
    """argparse type for ``--interval``: seconds, or 0 to publish once and exit."""
    try:
        seconds = float(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(f"interval must be a number, got {value!r}") from error
    if seconds < 0:
        raise argparse.ArgumentTypeError(f"interval must not be negative, got {seconds}")
    return seconds


def _add_experiment_options(parser: argparse.ArgumentParser) -> None:
    """Options every ``reproduce-*``/``finalize-*`` subcommand shares."""
    parser.add_argument("--features", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--device", type=_device)


def _add_parallel_options(parser: argparse.ArgumentParser) -> None:
    """Options that let one protocol's seeds run as concurrent processes."""
    parser.add_argument(
        "--seed",
        type=int,
        action="append",
        default=[],
        metavar="N",
        help=(
            "run only this seed (repeatable). Each process writes its own "
            "seed directory and leaves summary.json alone, so seeds can run in "
            "parallel; rebuild the summary afterwards with --collect-only"
        ),
    )
    parser.add_argument(
        "--collect-only",
        action="store_true",
        help="rebuild summary.json from the seed records on disk without training",
    )


def _add_drift_option(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--allow-environment-drift",
        action="store_true",
        help=(
            "resume a run recorded on different hardware; PyTorch/CUDA/cuBLAS "
            "settings must still match, and each seed records its own environment"
        ),
    )


def _add_data_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--data-root", type=Path, required=True)
    parser.add_argument("--id-column", default="clip_id")
    parser.add_argument("--label-column", default="label")
    parser.add_argument("--subject-column", default="subject_id")
    parser.add_argument("--video-extension", default=".mp4")


def _load_contract(args: argparse.Namespace, *, require_videos: bool = True) -> DatasetContract:
    return load_dataset_contract(
        args.data_root,
        id_column=args.id_column,
        label_column=args.label_column,
        subject_column=args.subject_column or None,
        video_extension=args.video_extension,
        require_videos=require_videos,
    )


def _validate(args: argparse.Namespace) -> int:
    contract = _load_contract(args)
    counts = {split: len(records) for split, records in contract.splits.items()}
    print(
        "EngageNet contract valid | "
        f"train={counts['train']} valid={counts['valid']} test={counts['test']}"
    )
    return 0


def _extract(args: argparse.Namespace) -> int:
    from zani_ai.engagement.extraction import extract_contract_parallel

    contract = _load_contract(args)
    manifest = extract_contract_parallel(
        contract,
        args.face_landmarker_model,
        args.output,
        workers=args.workers,
        progress_every=args.progress_every,
        max_excluded_fraction=args.max_excluded_fraction,
    )
    print(
        f"Features extracted | included={len(manifest.included)} "
        f"excluded={len(manifest.excluded)} | {args.output / 'manifest.json'}"
    )
    return 0


def _extract_raw(args: argparse.Namespace) -> int:
    from zani_ai.engagement.raw_cache import extract_raw_contract_parallel

    contract = _load_contract(args)
    manifest = extract_raw_contract_parallel(
        contract,
        args.face_landmarker_model,
        args.output,
        workers=args.workers,
        progress_every=args.progress_every,
        max_excluded_fraction=args.max_excluded_fraction,
        sample_fps=args.sample_fps,
        require_coverage=not args.keep_low_coverage,
    )
    print(
        f"Raw features extracted | included={len(manifest.included)} "
        f"excluded={len(manifest.excluded)} | {args.output / manifest.schema / 'manifest.json'}"
    )
    return 0


def _build_features(args: argparse.Namespace) -> int:
    from zani_ai.engagement import representations
    from zani_ai.engagement.features import get_schema
    from zani_ai.engagement.representations import (
        LandmarkSequenceRepresentation,
        TokenRepresentation,
        ZeroPlaceholderLandmarkSequenceRepresentation,
        ZeroPlaceholderTokenRepresentation,
    )

    contract = _load_contract(args, require_videos=False)
    representation: representations.Representation
    if args.schema.startswith("landmark_78"):
        sequence_type: type[LandmarkSequenceRepresentation] = (
            ZeroPlaceholderLandmarkSequenceRepresentation
            if "placeholder" in args.schema
            else LandmarkSequenceRepresentation
        )
        representation = sequence_type.for_sample_fps(args.sample_fps)
        if representation.name != args.schema:
            raise ValueError(
                f"--schema {args.schema} does not match --sample-fps {args.sample_fps}, "
                f"which produces {representation.name}"
            )
    elif args.schema == "mediapipe_98_placeholder_v1":
        representation = ZeroPlaceholderTokenRepresentation(get_schema(args.schema))
    else:
        representation = TokenRepresentation(get_schema(args.schema))
    manifest_path = representations.build_feature_manifest(
        args.raw_root, args.output, representation, contract
    )
    print(f"Feature manifest built | schema={args.schema} | {manifest_path}")
    return 0


def _audit_frame_gate(args: argparse.Namespace) -> int:
    from zani_ai.engagement.frame_gate_audit import (
        audit_frame_gate_gap,
        write_frame_gate_audit,
    )

    report = audit_frame_gate_gap(_load_contract(args, require_videos=False), args.raw_root)
    write_frame_gate_audit(report, args.output)
    print(f"Frame gate audit | mismatched={report['mismatch_clip_count']} | {args.output}")
    return 0


def _resolve_device(args: argparse.Namespace) -> str:
    """The requested device, defaulting to CUDA only when it is actually there."""
    import torch

    return args.device or ("cuda" if torch.cuda.is_available() else "cpu")


def _analyze_label_reliability(args: argparse.Namespace) -> int:
    from zani_ai.engagement.reliability import analyze_label_reliability

    result = analyze_label_reliability(
        args.features,
        args.baseline_output,
        args.output,
        device=_resolve_device(args),
    )
    print(
        f"Label reliability analysis complete | decision={result.decision} "
        f"| {result.manifest_path}",
        flush=True,
    )
    return 0


def _reproduce(protocol: str) -> Command:
    """Build the ``reproduce-<protocol>`` handler.

    Every protocol runs the same driver and differs only by its spec, so the
    handlers are generated rather than written out once per experiment.
    Imports stay inside so ``--help`` does not pay for loading torch.
    """

    def handler(args: argparse.Namespace) -> int:
        from zani_ai.engagement.experiment import SPECS, collect_only, reproduce_experiment

        shared = {
            "device": _resolve_device(args),
            "graph_path": getattr(args, "graph", None),
            "reliability_path": getattr(args, "reliability", None),
            "stage1_path": getattr(args, "stage1", None),
            "allow_environment_drift": args.allow_environment_drift,
        }
        if args.collect_only:
            result = collect_only(SPECS[protocol], args.features, args.output, **shared)
            print(
                f"{protocol} summary collected "
                f"| seeds={','.join(map(str, result.completed_seeds))} "
                f"| {result.summary_path}",
                flush=True,
            )
            return 0

        seeds = tuple(args.seed) or None
        result = reproduce_experiment(
            SPECS[protocol],
            args.features,
            args.output,
            seeds=seeds,
            # A subset means this process is one of several running in
            # parallel, so it must not write the shared summary.
            collect=seeds is None,
            **shared,
        )
        if seeds is None:
            print(
                f"{protocol} reproduction complete "
                f"| seeds={','.join(map(str, result.completed_seeds))} "
                f"| {result.summary_path}",
                flush=True,
            )
        else:
            print(
                f"{protocol} seeds {','.join(map(str, seeds))} done "
                f"| run --collect-only to rebuild {result.summary_path}",
                flush=True,
            )
        return 0

    return handler


def _finalize(protocol: str) -> Command:
    """Build the ``finalize-<protocol>`` handler; see :func:`_reproduce`."""

    def handler(args: argparse.Namespace) -> int:
        from zani_ai.engagement.experiment import SPECS
        from zani_ai.engagement.report import finalize_experiment

        results_path = finalize_experiment(
            SPECS[protocol],
            args.features,
            args.output,
            device=_resolve_device(args),
        )
        print(f"{protocol} Test evaluation complete | {results_path}", flush=True)
        return 0

    return handler


def _train(args: argparse.Namespace) -> int:
    from zani_ai.engagement.training import TrainingConfig, train_model

    manifest = args.features / "manifest.json"
    if not manifest.is_file():
        raise FileNotFoundError(f"feature manifest not found: {manifest}")
    result = train_model(
        TrainingConfig(
            features_root=args.features,
            output_dir=args.output,
            max_epochs=args.epochs,
            batch_size=args.batch_size,
            learning_rate=args.learning_rate,
            patience=args.patience,
            seed=args.seed,
            device=_resolve_device(args),
            class_weighting=args.class_weighting,
        )
    )
    if result.test is None:
        raise RuntimeError("single-run training did not produce Test metrics")
    print(
        f"Training complete | best_epoch={result.best_epoch} "
        f"test_macro_f1={result.test.macro_f1:.4f} | {result.checkpoint_path}"
    )
    return 0


def _publish_results(args: argparse.Namespace) -> int:
    """Publish metrics once, or every ``--interval`` seconds until killed."""
    source_label = args.source or default_source_label()
    # Checked before anything is locked or written: collect_metrics returns an
    # empty list for a root that is not there, so a typo'd --artifacts would
    # otherwise buy a loop that logs nothing and publishes nothing forever.
    if not args.artifacts.is_dir():
        raise ValueError(f"--artifacts is not an existing directory: {args.artifacts}")
    # Both checks belong outside the loop: a wrong branch or a second publisher
    # is a mistake to report now, not something to retry every interval.
    require_results_branch(args.worktree, args.branch)
    lock = DirectoryLock(
        args.worktree / LOCK_FILENAME,
        busy_message=(
            f"another publisher already holds {args.worktree / LOCK_FILENAME}; "
            "stop it before starting a second one"
        ),
    )
    if args.interval == 0:
        try:
            with lock:
                written = publish_once(
                    artifacts_root=args.artifacts,
                    worktree=args.worktree,
                    source_label=source_label,
                    branch=args.branch,
                )
        except LockUnavailableError:
            # The documented setup runs the periodic publisher too, and it holds
            # the lock for its whole life. This snapshot is on disk and that loop
            # will send it, so a single shot has nothing to report as a failure;
            # saying otherwise teaches the operator to ignore the message.
            print(
                f"a periodic publisher holds {lock.path}; "
                "it will publish this snapshot within one interval"
            )
            return 0
        print(f"published {written} file(s)")
        return 0
    # A second loop in one worktree is a real misconfiguration: two processes
    # would race on the same index.lock. That one stays fatal.
    with lock:
        run_forever(
            artifacts_root=args.artifacts,
            worktree=args.worktree,
            source_label=source_label,
            branch=args.branch,
            interval=args.interval,
        )


def _export(args: argparse.Namespace) -> int:
    from zani_ai.engagement.export import DeploymentMetadata, export_onnx
    from zani_ai.engagement.training import load_checkpoint

    if not args.checkpoint.is_file():
        raise FileNotFoundError(f"trained checkpoint not found: {args.checkpoint}")
    model = load_checkpoint(args.checkpoint)
    result = export_onnx(model, DeploymentMetadata.default(), args.output)
    print(f"ONNX export complete | {result.model_path} | {result.metadata_path}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m zani_ai engagement",
        description="EngageNet MediaPipe reproduction pipeline",
    )
    commands = parser.add_subparsers(dest="command", required=True)

    validate = commands.add_parser("validate", help="validate official-style dataset files")
    _add_data_options(validate)
    validate.set_defaults(handler=_validate)

    extract = commands.add_parser("extract", help="extract mediapipe_98_v1 features")
    _add_data_options(extract)
    extract.add_argument("--face-landmarker-model", type=Path, required=True)
    extract.add_argument("--output", type=Path, required=True)
    extract.add_argument("--max-excluded-fraction", type=float, default=0.05)
    extract.add_argument("--workers", type=int, default=2)
    extract.add_argument("--progress-every", type=int, default=25)
    extract.set_defaults(handler=_extract)

    extract_raw = commands.add_parser(
        "extract-raw", help="extract and cache raw per-frame MediaPipe output"
    )
    _add_data_options(extract_raw)
    extract_raw.add_argument("--face-landmarker-model", type=Path, required=True)
    extract_raw.add_argument("--output", type=Path, required=True)
    extract_raw.add_argument("--workers", type=int, default=4)
    extract_raw.add_argument("--sample-fps", type=_sample_fps, default=10.0)
    extract_raw.add_argument("--progress-every", type=int, default=25)
    extract_raw.add_argument("--max-excluded-fraction", type=float, default=0.05)
    extract_raw.add_argument(
        "--keep-low-coverage",
        action="store_true",
        help=(
            "cache every decodable clip instead of dropping the ones that fail the "
            "segment-coverage rule; the choice is stamped into the extraction fingerprint"
        ),
    )
    extract_raw.set_defaults(handler=_extract_raw)

    build_features = commands.add_parser(
        "build-features", help="derive a feature representation cache from the raw cache"
    )
    build_features.add_argument("--raw-root", type=Path, required=True)
    build_features.add_argument("--output", type=Path, required=True)
    build_features.add_argument(
        "--schema",
        choices=(
            "mediapipe_98_v1",
            "mediapipe_98_placeholder_v1",
            "mediapipe_132_v1",
            "landmark_78_v1",
            "landmark_78_300_v1",
            "landmark_78_placeholder_v1",
            "landmark_78_300_placeholder_v1",
        ),
        required=True,
    )
    build_features.add_argument(
        "--sample-fps",
        type=_sample_fps,
        default=10.0,
        help="rate the raw cache was extracted at; must match --schema",
    )
    _add_data_options(build_features)
    build_features.set_defaults(handler=_build_features)

    audit_frame_gate = commands.add_parser(
        "audit-frame-gate",
        help="count raw clips accepted offline but rejected by the browser frame gate",
    )
    _add_data_options(audit_frame_gate)
    audit_frame_gate.add_argument("--raw-root", type=Path, required=True)
    audit_frame_gate.add_argument("--output", type=Path, required=True)
    audit_frame_gate.set_defaults(handler=_audit_frame_gate)

    train = commands.add_parser("train", help="train and evaluate the Transformer")
    train.add_argument("--features", type=Path, required=True)
    train.add_argument("--output", type=Path, required=True)
    train.add_argument("--epochs", type=int, default=200)
    train.add_argument("--batch-size", type=int, default=32)
    train.add_argument("--learning-rate", type=float, default=1e-4)
    train.add_argument("--patience", type=int, default=20)
    train.add_argument("--seed", type=int, default=42)
    train.add_argument("--device", type=_device)
    train.add_argument(
        "--class-weighting",
        choices=CLASS_WEIGHTING_SCHEMES,
        default="none",
        help="per-class loss weighting; 'balanced' inverts class frequency",
    )
    train.set_defaults(handler=_train)

    analyze_reliability = commands.add_parser(
        "analyze-label-reliability",
        help="classify Train/Validation clips by E0 five-seed disagreement",
    )
    analyze_reliability.add_argument("--features", type=Path, required=True)
    analyze_reliability.add_argument("--baseline-output", type=Path, required=True)
    analyze_reliability.add_argument("--output", type=Path, required=True)
    analyze_reliability.add_argument("--device", type=_device)
    analyze_reliability.set_defaults(handler=_analyze_label_reliability)

    for command, protocol, description in (
        ("e0", "E0", "E0"),
        ("e0a", "E0-A", "E0-A"),
        ("e0b", "E0-B", "E0-B"),
        ("e0c", "E0-C", "E0-C (balanced class weights)"),
        ("e0d", "E0-D", "E0-D (sqrt-balanced class weights)"),
        ("e0e", "E0-E", "E0-E (focal loss on sqrt-balanced weights)"),
        ("e0f", "E0-F", "E0-F (balanced sampler, unweighted loss)"),
        ("e0g", "E0-G", "E0-G (aligned training schedule)"),
        ("e0h", "E0-H", "E0-H (SORD soft ordinal targets)"),
        ("e0i", "E0-I", "E0-I (label-reliability curriculum)"),
        ("e0j", "E0-J", "E0-J (zero placeholders for missing faces)"),
        ("e0k", "E0-K", "E0-K (lr 1e-3, step decay, 300 epochs)"),
        ("e0l", "E0-L", "E0-L (K-1 순서형 이진 헤드, 동결 백본 2단계)"),
        ("e0-10", "E0-10", "E0-10 (E0와 동일, 10-seed 비교 기준선)"),
        ("e1", "E1", "E1 (ST-GCN)"),
        ("e1a", "E1-A", "E1-A (ST-GCN, 원논문 학습 조건)"),
        ("e1b", "E1-B", "E1-B (ST-GCN, 30fps 300프레임)"),
        ("e1p", "E1-P", "E1-P (문헌 정합 non-ordinal ST-GCN, K=1 그래프)"),
    ):
        reproduce = commands.add_parser(
            f"reproduce-{command}",
            # Seed counts differ per protocol since S15P11A105-238 (five for
            # everything through E0-L, ten for E0-10 and later candidates), so
            # the count lives in the spec and in ai/README.md, not here.
            help=f"run the validation-only multi-seed {description} protocol",
        )
        _add_experiment_options(reproduce)
        _add_drift_option(reproduce)
        _add_parallel_options(reproduce)
        if protocol == "E1":
            reproduce.add_argument(
                "--graph",
                type=Path,
                help=(
                    "landmark graph .npz; defaults to ZANI_LANDMARK_GRAPH, then "
                    "landmark_78_v1_graph.npz beside or above --features"
                ),
            )
        if protocol == "E0-I":
            reproduce.add_argument(
                "--reliability",
                type=Path,
                required=True,
                help="go reliability_manifest.json produced by analyze-label-reliability",
            )
        if protocol == "E0-L":
            reproduce.add_argument(
                "--stage1",
                type=Path,
                required=True,
                help=(
                    "completed E0 output directory; seed n freezes the backbone of "
                    "<dir>/seed-<n>/best.pt"
                ),
            )
        reproduce.set_defaults(handler=_reproduce(protocol))

        finalize = commands.add_parser(
            f"finalize-{command}",
            help=(f"evaluate frozen {description} checkpoints once and write JSON results"),
        )
        _add_experiment_options(finalize)
        finalize.set_defaults(handler=_finalize(protocol))

    publish = commands.add_parser(
        "publish-results",
        help="copy training metrics into the results-branch worktree and push them",
    )
    publish.add_argument("--artifacts", type=Path, required=True)
    publish.add_argument("--worktree", type=Path, required=True)
    publish.add_argument(
        "--source",
        type=_source,
        help="one path segment inside the branch; defaults to the short hostname",
    )
    publish.add_argument("--branch", default=DEFAULT_RESULTS_BRANCH)
    publish.add_argument(
        "--interval",
        type=_interval,
        default=300.0,
        help="seconds between snapshots; 0 publishes once and exits",
    )
    publish.set_defaults(handler=_publish_results)

    export = commands.add_parser("export", help="export a trained checkpoint to ONNX")
    export.add_argument("--checkpoint", type=Path, required=True)
    export.add_argument("--output", type=Path, required=True)
    export.set_defaults(handler=_export)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    handler = args.handler
    try:
        return int(handler(args))
    except (DatasetContractError, FileNotFoundError, ValueError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        return 2


__all__ = ["build_parser", "main"]
