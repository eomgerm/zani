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
from zani_ai.engagement.features import SCHEMA_NAME, SCHEMAS, FeatureSchema, get_schema
from zani_ai.engagement.landmark_graph import GRAPH_VERSION
from zani_ai.engagement.model import EngagementTransformer


@dataclass(frozen=True, slots=True)
class DeploymentMetadata:
    schema: str
    input_name: str
    input_shape: tuple[str | int, ...]
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

    @classmethod
    def for_schema(cls, schema: FeatureSchema) -> DeploymentMetadata:
        return cls(
            schema=schema.name,
            input_name="tokens",
            input_shape=("batch", 20, schema.token_feature_count),
            output_name="logits",
            labels=LABELS,
            window_seconds=10.0,
            segment_count=20,
            sample_fps=10.0,
        )

    @classmethod
    def for_stgcn(cls) -> DeploymentMetadata:
        """Deployment metadata for the E1 ST-GCN landmark-sequence model.

        Input is a fixed ``[batch, 3, 100, 78]`` landmark-sequence tensor
        (channel, time, node) -- see ``representations.LandmarkSequenceRepresentation``
        and ``stgcn.EngagementSTGCN`` -- rather than the Transformer family's
        ``[batch, 20, D]`` token sequence, so this does not go through
        ``for_schema``/``SCHEMAS`` (which are token-schema-only).
        """
        return cls(
            schema=GRAPH_VERSION,
            input_name="sequence",
            input_shape=("batch", 3, 100, 78),
            output_name="logits",
            labels=LABELS,
            window_seconds=10.0,
            segment_count=100,
            sample_fps=10.0,
        )


@dataclass(frozen=True, slots=True)
class ExportResult:
    model_path: Path
    metadata_path: Path


class _CoralClassProbModule(torch.nn.Module):
    """Wrap a CORAL-head model so it exports [B,4] class probabilities.

    Mirrors ``training.CoralObjective.class_probs`` exactly, using only
    vectorized tensor ops (no Python loops) so it traces cleanly to ONNX.
    """

    def __init__(self, model: EngagementTransformer) -> None:
        super().__init__()
        self.model = model

    def forward(self, tokens: torch.Tensor) -> torch.Tensor:
        z = self.model(tokens)  # [B, num_classes-1] threshold logits
        pg = torch.sigmoid(z)  # P(y>k) [B, num_classes-1]
        first = 1 - pg[:, :1]
        mid = pg[:, :-1] - pg[:, 1:]
        last = pg[:, -1:]
        p = torch.cat([first, mid, last], dim=1).clamp_min(0)
        return p / p.sum(dim=1, keepdim=True)


def export_onnx(
    model: torch.nn.Module,
    metadata: DeploymentMetadata,
    output_dir: Path,
    *,
    opset_version: int = 18,
) -> ExportResult:
    """Export only after ONNX structure and numerical parity both validate.

    ``model`` is either an ``EngagementTransformer`` (token schemas, e.g.
    E0/E0-A/E0-B; ``metadata.input_shape`` is ``("batch", 20, D)``) or an
    ``EngagementSTGCN`` (E1's landmark-sequence schema, produced by
    :meth:`DeploymentMetadata.for_stgcn`; ``metadata.input_shape`` is
    ``("batch", 3, 100, 78)``). The example tensor and ONNX/ORT parity check
    below are shape-generic and apply identically to both; only the
    declared-schema validation branches on which family ``metadata`` names.
    """
    if metadata.schema == GRAPH_VERSION:
        if metadata.input_shape != ("batch", 3, 100, 78):
            raise ValueError("deployment metadata does not match the ST-GCN landmark schema")
    else:
        if metadata.schema not in SCHEMAS:
            raise ValueError(f"unknown deployment schema: {metadata.schema}")
        seg, dim = metadata.input_shape[1:]
        if (seg, dim) != (20, get_schema(metadata.schema).token_feature_count):
            raise ValueError("deployment metadata does not match its declared schema")
    if tuple(metadata.labels) != LABELS:
        raise ValueError("deployment label order does not match the training contract")
    output_dir.mkdir(parents=True, exist_ok=True)
    model_path = output_dir / "engagement.onnx"
    metadata_path = output_dir / "engagement.metadata.json"
    temporary_model = output_dir / ".engagement.onnx.tmp"
    temporary_metadata = output_dir / ".engagement.metadata.json.tmp"
    shape_after_batch = metadata.input_shape[1:]
    element_count = 1
    for axis_size in shape_after_batch:
        element_count *= axis_size
    # Byte-identical to the previous `torch.arange(seg * dim).reshape(1, seg, dim)`
    # for the 2-dim token form; generalizes to the 3-dim ST-GCN sequence form.
    example = (
        torch.arange(element_count, dtype=torch.float32).reshape(1, *shape_after_batch) / 1000
    )
    model = model.cpu().eval()
    export_module: torch.nn.Module = (
        _CoralClassProbModule(model).eval()
        if getattr(getattr(model, "config", None), "head", None) == "coral"
        else model
    )
    try:
        batch = torch.export.Dim("batch", min=1)
        with warnings.catch_warnings():
            warnings.filterwarnings(
                "ignore",
                message=r"`isinstance\(treespec, LeafSpec\)` is deprecated",
                category=FutureWarning,
            )
            program = torch.onnx.export(
                export_module,
                (example,),
                input_names=[metadata.input_name],
                output_names=[metadata.output_name],
                # Positional form (matching the `(example,)` args tuple) rather than a
                # dict keyed by `metadata.input_name`: the dict form requires the key to
                # match the wrapped module's actual `forward` parameter name (`tokens`
                # for EngagementTransformer, but `x` for EngagementSTGCN), which
                # `metadata.input_name` (the ONNX graph's input tensor name) need not be.
                dynamic_shapes=({0: batch},),
                opset_version=opset_version,
                dynamo=True,
                external_data=False,
            )
        if program is None:
            raise RuntimeError("PyTorch did not return an ONNX program")
        program.save(temporary_model, external_data=False)
        exported = onnx.load(temporary_model)
        onnx.checker.check_model(exported)
        expected = export_module(example).detach().numpy()
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
