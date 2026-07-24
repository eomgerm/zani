from __future__ import annotations

import argparse
import sys
from collections.abc import Callable, Sequence
from pathlib import Path

from zani_ai.engagement.contracts import (
    DatasetContract,
    DatasetContractError,
    load_dataset_contract,
)

type Command = Callable[[argparse.Namespace], int]


def _add_data_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--data-root", type=Path, required=True)
    parser.add_argument("--id-column", default="clip_id")
    parser.add_argument("--label-column", default="label")
    parser.add_argument("--subject-column", default="subject_id")
    parser.add_argument("--video-extension", default=".mp4")


def _load_contract(args: argparse.Namespace) -> DatasetContract:
    return load_dataset_contract(
        args.data_root,
        id_column=args.id_column,
        label_column=args.label_column,
        subject_column=args.subject_column or None,
        video_extension=args.video_extension,
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
    )
    print(
        f"Raw features extracted | included={len(manifest.included)} "
        f"excluded={len(manifest.excluded)} | {args.output / manifest.schema / 'manifest.json'}"
    )
    return 0


def _build_features(args: argparse.Namespace) -> int:
    from zani_ai.engagement import representations
    from zani_ai.engagement.features import get_schema
    from zani_ai.engagement.representations import TokenRepresentation

    contract = _load_contract(args)
    representation = TokenRepresentation(get_schema(args.schema))
    manifest_path = representations.build_feature_manifest(
        args.raw_root, args.output, representation, contract
    )
    print(f"Feature manifest built | schema={args.schema} | {manifest_path}")
    return 0


def _reproduce_e0a(args: argparse.Namespace) -> int:
    import torch

    from zani_ai.engagement.experiment import E0A_SPEC, reproduce_experiment

    device = args.device or ("cuda" if torch.cuda.is_available() else "cpu")
    result = reproduce_experiment(E0A_SPEC, args.features, args.output, device=device)
    print(
        f"E0-A reproduction complete | seeds={','.join(map(str, result.completed_seeds))} "
        f"| {result.summary_path}",
        flush=True,
    )
    return 0


def _finalize_e0a(args: argparse.Namespace) -> int:
    import torch

    from zani_ai.engagement.experiment import E0A_SPEC
    from zani_ai.engagement.report import finalize_experiment

    device = args.device or ("cuda" if torch.cuda.is_available() else "cpu")
    report_path = finalize_experiment(
        E0A_SPEC,
        args.features,
        args.output,
        device=device,
        face_landmarker_model=args.face_landmarker_model,
        preparation_manifest=args.preparation_manifest,
        threshold_manifest=args.threshold_manifest,
    )
    print(f"E0-A Test evaluation and report complete | {report_path}", flush=True)
    return 0


def _train(args: argparse.Namespace) -> int:
    import torch

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
            device=args.device or ("cuda" if torch.cuda.is_available() else "cpu"),
            use_class_weights=args.class_weights,
        )
    )
    if result.test is None:
        raise RuntimeError("single-run training did not produce Test metrics")
    print(
        f"Training complete | best_epoch={result.best_epoch} "
        f"test_macro_f1={result.test.macro_f1:.4f} | {result.checkpoint_path}"
    )
    return 0


def _reproduce_e0(args: argparse.Namespace) -> int:
    import torch

    from zani_ai.engagement.experiment import reproduce_e0

    device = args.device or ("cuda" if torch.cuda.is_available() else "cpu")
    result = reproduce_e0(args.features, args.output, device=device)
    print(
        f"E0 reproduction complete | seeds={','.join(map(str, result.completed_seeds))} "
        f"| {result.summary_path}",
        flush=True,
    )
    return 0


def _finalize_e0(args: argparse.Namespace) -> int:
    import torch

    from zani_ai.engagement.report import finalize_e0

    device = args.device or ("cuda" if torch.cuda.is_available() else "cpu")
    report_path = finalize_e0(
        args.features,
        args.output,
        device=device,
        face_landmarker_model=args.face_landmarker_model,
        preparation_manifest=args.preparation_manifest,
        threshold_manifest=args.threshold_manifest,
    )
    print(f"E0 Test evaluation and report complete | {report_path}", flush=True)
    return 0


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
    extract_raw.add_argument("--progress-every", type=int, default=25)
    extract_raw.add_argument("--max-excluded-fraction", type=float, default=0.05)
    extract_raw.set_defaults(handler=_extract_raw)

    build_features = commands.add_parser(
        "build-features", help="derive a feature representation cache from the raw cache"
    )
    build_features.add_argument("--raw-root", type=Path, required=True)
    build_features.add_argument("--output", type=Path, required=True)
    build_features.add_argument(
        "--schema", choices=("mediapipe_98_v1", "mediapipe_132_v1"), required=True
    )
    _add_data_options(build_features)
    build_features.set_defaults(handler=_build_features)

    train = commands.add_parser("train", help="train and evaluate the Transformer")
    train.add_argument("--features", type=Path, required=True)
    train.add_argument("--output", type=Path, required=True)
    train.add_argument("--epochs", type=int, default=200)
    train.add_argument("--batch-size", type=int, default=32)
    train.add_argument("--learning-rate", type=float, default=1e-4)
    train.add_argument("--patience", type=int, default=20)
    train.add_argument("--seed", type=int, default=42)
    train.add_argument("--device", choices=("cpu", "cuda"))
    train.add_argument("--class-weights", action="store_true")
    train.set_defaults(handler=_train)

    reproduce_e0 = commands.add_parser(
        "reproduce-e0", help="run the validation-only five-seed E0 protocol"
    )
    reproduce_e0.add_argument("--features", type=Path, required=True)
    reproduce_e0.add_argument("--output", type=Path, required=True)
    reproduce_e0.add_argument("--device", choices=("cpu", "cuda"))
    reproduce_e0.set_defaults(handler=_reproduce_e0)

    finalize_e0_parser = commands.add_parser(
        "finalize-e0", help="evaluate frozen E0 checkpoints once and write the HTML report"
    )
    finalize_e0_parser.add_argument("--features", type=Path, required=True)
    finalize_e0_parser.add_argument("--output", type=Path, required=True)
    finalize_e0_parser.add_argument("--device", choices=("cpu", "cuda"))
    finalize_e0_parser.add_argument("--face-landmarker-model", type=Path)
    finalize_e0_parser.add_argument("--preparation-manifest", type=Path)
    finalize_e0_parser.add_argument("--threshold-manifest", type=Path)
    finalize_e0_parser.set_defaults(handler=_finalize_e0)

    reproduce_e0a = commands.add_parser(
        "reproduce-e0a", help="run the validation-only five-seed E0-A protocol"
    )
    reproduce_e0a.add_argument("--features", type=Path, required=True)
    reproduce_e0a.add_argument("--output", type=Path, required=True)
    reproduce_e0a.add_argument("--device", choices=("cpu", "cuda"))
    reproduce_e0a.set_defaults(handler=_reproduce_e0a)

    finalize_e0a_parser = commands.add_parser(
        "finalize-e0a", help="evaluate frozen E0-A checkpoints once and write the HTML report"
    )
    finalize_e0a_parser.add_argument("--features", type=Path, required=True)
    finalize_e0a_parser.add_argument("--output", type=Path, required=True)
    finalize_e0a_parser.add_argument("--device", choices=("cpu", "cuda"))
    finalize_e0a_parser.add_argument("--face-landmarker-model", type=Path)
    finalize_e0a_parser.add_argument("--preparation-manifest", type=Path)
    finalize_e0a_parser.add_argument("--threshold-manifest", type=Path)
    finalize_e0a_parser.set_defaults(handler=_finalize_e0a)

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
