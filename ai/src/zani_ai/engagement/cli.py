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
    from zani_ai.engagement.extraction import MediaPipeFaceLandmarker, extract_contract

    contract = _load_contract(args)
    with MediaPipeFaceLandmarker(args.face_landmarker_model) as landmarker:
        manifest = extract_contract(
            contract,
            landmarker,
            args.output,
            max_excluded_fraction=args.max_excluded_fraction,
        )
    print(
        f"Features extracted | included={len(manifest.included)} "
        f"excluded={len(manifest.excluded)} | {args.output / 'manifest.json'}"
    )
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
    print(
        f"Training complete | best_epoch={result.best_epoch} "
        f"test_macro_f1={result.test.macro_f1:.4f} | {result.checkpoint_path}"
    )
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
    extract.set_defaults(handler=_extract)

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
