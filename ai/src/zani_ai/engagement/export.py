from __future__ import annotations

import json
import warnings
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch

from zani_ai.engagement.contracts import LABELS
from zani_ai.engagement.features import SCHEMA_NAME
from zani_ai.engagement.model import EngagementTransformer


@dataclass(frozen=True, slots=True)
class DeploymentMetadata:
    schema: str
    input_name: str
    input_shape: tuple[str | int, int, int]
    output_name: str
    labels: tuple[str, ...]
    window_seconds: float
    segment_count: int
    sample_fps: float

    @classmethod
    def default(cls) -> DeploymentMetadata:
        return cls(
            schema=SCHEMA_NAME,
            input_name="tokens",
            input_shape=("batch", 20, 98),
            output_name="logits",
            labels=LABELS,
            window_seconds=10.0,
            segment_count=20,
            sample_fps=10.0,
        )


@dataclass(frozen=True, slots=True)
class ExportResult:
    model_path: Path
    metadata_path: Path


def export_onnx(
    model: EngagementTransformer,
    metadata: DeploymentMetadata,
    output_dir: Path,
    *,
    opset_version: int = 18,
) -> ExportResult:
    """Export only after ONNX structure and numerical parity both validate."""
    if metadata.schema != SCHEMA_NAME or metadata.input_shape[1:] != (20, 98):
        raise ValueError("deployment metadata does not match mediapipe_98_v1")
    if tuple(metadata.labels) != LABELS:
        raise ValueError("deployment label order does not match the training contract")
    output_dir.mkdir(parents=True, exist_ok=True)
    model_path = output_dir / "engagement.onnx"
    metadata_path = output_dir / "engagement.metadata.json"
    temporary_model = output_dir / ".engagement.onnx.tmp"
    temporary_metadata = output_dir / ".engagement.metadata.json.tmp"
    example = torch.arange(20 * 98, dtype=torch.float32).reshape(1, 20, 98) / 1000
    model = model.cpu().eval()
    try:
        batch = torch.export.Dim("batch", min=1)
        with warnings.catch_warnings():
            warnings.filterwarnings(
                "ignore",
                message=r"`isinstance\(treespec, LeafSpec\)` is deprecated",
                category=FutureWarning,
            )
            program = torch.onnx.export(
                model,
                (example,),
                input_names=[metadata.input_name],
                output_names=[metadata.output_name],
                dynamic_shapes={metadata.input_name: {0: batch}},
                opset_version=opset_version,
                dynamo=True,
                external_data=False,
            )
        if program is None:
            raise RuntimeError("PyTorch did not return an ONNX program")
        program.save(temporary_model, external_data=False)
        exported = onnx.load(temporary_model)
        onnx.checker.check_model(exported)
        expected = model(example).detach().numpy()
        session = ort.InferenceSession(
            str(temporary_model), providers=["CPUExecutionProvider"]
        )
        actual = session.run(
            [metadata.output_name], {metadata.input_name: example.numpy()}
        )[0]
        np.testing.assert_allclose(actual, expected, rtol=1e-4, atol=1e-5)
        temporary_metadata.write_text(
            json.dumps(asdict(metadata), ensure_ascii=False, indent=2), encoding="utf-8"
        )
        temporary_model.replace(model_path)
        temporary_metadata.replace(metadata_path)
    finally:
        temporary_model.unlink(missing_ok=True)
        temporary_metadata.unlink(missing_ok=True)
    return ExportResult(model_path, metadata_path)


__all__ = ["DeploymentMetadata", "ExportResult", "export_onnx"]
