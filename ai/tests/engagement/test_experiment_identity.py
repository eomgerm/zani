"""Locks the reproducibility identity of every experiment protocol.

``experiment._build_configuration`` produces the dict whose canonical SHA-256
(``configuration_sha256``) decides whether an existing summary's seeds may be
reused. Any change to that dict silently invalidates every completed run, so
the hashes below are pinned: they were measured before the remote-L40S
portability work and must survive it unchanged.

If a test here fails, either the change genuinely alters the protocol -- in
which case it needs a new protocol identity, not an edited constant -- or it
leaked an environment detail (a path, a device index, a hostname) into
``configuration``.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from zani_ai.engagement.experiment import (
    E0_SPEC,
    E0A_SPEC,
    E0B_SPEC,
    E0C_SPEC,
    E0D_SPEC,
    E1_SPEC,
    SPECS,
    ExperimentSpec,
    _build_configuration,
    _canonical_hash,
    _environment_matches,
    _graph_record,
    _validate_inputs_identity,
    stgcn_model_builder,
)

#: (spec, device) -> configuration_sha256, measured on the pre-change tree.
BASELINE_HASHES: dict[tuple[str, str], str] = {
    ("E0", "cpu"): "0f6dfe01f1a4369766c93b64250d9f0927b929fa6fc811d838f7059879f74f0a",
    ("E0", "cuda"): "d5380ce215c1ddb69cccb931b8dd647780edaee21521fc0f854ea49fcafc7656",
    ("E0-A", "cpu"): "8abe19f5273d54d63b3518ea01755fe4a0520008e6e4dc934a4e3b6facbd6119",
    ("E0-A", "cuda"): "cd7933ba51f218cb2129f9e2f985571afa3e78c4a72ce1d0f6444adbcf8b232d",
    ("E0-B", "cpu"): "dec33b146aceae62b693120fb0089e3a5a350b08cbe6b068a8190ada43fd545f",
    ("E0-B", "cuda"): "d0d1162228bcbd42b45045e5a9084ad54e32f2483af47e4982672045c7d4b175",
    ("E0-C", "cpu"): "45d96d1fac4109e99580586b0c94d84da48b883de1e51e25640ef90aeed839cf",
    ("E0-C", "cuda"): "05f8a91f2a575c91d4f92c7970d1da589faef497fc210a0e767021a687e41e5b",
    ("E0-D", "cpu"): "124b3fca0d77a2e2c7e5dbfdae69c8e4f4815069f456499e8c6f2526d4abaf1f",
    ("E0-D", "cuda"): "7ac3903839d2d583c323aaf376ff1424814753dfe33059a949896525ae4a5049",
    ("E1", "cpu"): "9c6fb102d0b600d04dbd3c6b569a6f06248e5ae35efe603979401e8a4617e13d",
    ("E1", "cuda"): "69a87549d00a41de01eab8d94e97af40b2c6baed5012ecb9350438cd233c989e",
}

SPECS_TUPLE: tuple[ExperimentSpec, ...] = (
    E0_SPEC,
    E0A_SPEC,
    E0B_SPEC,
    E0C_SPEC,
    E0D_SPEC,
    E1_SPEC,
)


@pytest.mark.parametrize("spec", SPECS_TUPLE, ids=lambda spec: spec.protocol)
@pytest.mark.parametrize("device", ["cpu", "cuda"])
def test_configuration_hash_is_unchanged(spec: ExperimentSpec, device: str) -> None:
    configuration = _build_configuration(spec, device)

    assert _canonical_hash(configuration) == BASELINE_HASHES[(spec.protocol, device)]


@pytest.mark.parametrize("spec", SPECS_TUPLE, ids=lambda spec: spec.protocol)
@pytest.mark.parametrize("device", ["cpu", "cuda"])
def test_configuration_records_only_the_device_type(
    spec: ExperimentSpec, device: str
) -> None:
    """An explicit device index must not reach ``configuration``.

    ``cuda:2`` and ``cuda`` are the same protocol run on different hardware,
    so they must share an identity; the index belongs in ``environment``.
    """
    indexed = device if device == "cpu" else f"{device}:2"

    assert _build_configuration(spec, indexed) == _build_configuration(spec, device)


def test_every_protocol_is_covered() -> None:
    """Guards against a new spec being added without pinning its identity."""
    expected = {(spec.protocol, device) for spec in SPECS_TUPLE for device in ("cpu", "cuda")}

    assert set(BASELINE_HASHES) == expected


def test_spec_registry_matches_the_pinned_specs() -> None:
    assert {spec.protocol: spec for spec in SPECS_TUPLE} == SPECS


@pytest.mark.parametrize("spec", SPECS_TUPLE, ids=lambda spec: spec.protocol)
def test_unweighted_protocols_keep_the_original_false_literal(spec: ExperimentSpec) -> None:
    """`class_weighting` was a bool before the weighted protocols existed.

    Emitting the scheme name for unweighted specs too would have rewritten
    every existing protocol's hash and discarded their completed seeds.
    """
    recorded = _build_configuration(spec, "cuda")["class_weighting"]

    if spec.class_weighting == "none":
        assert recorded is False
    else:
        assert recorded == spec.class_weighting


def test_weighting_alone_separates_e0_from_its_variants() -> None:
    """E0-C/E0-D differ from E0 in exactly one field, so the hashes must differ."""
    base = _build_configuration(E0_SPEC, "cuda")

    for spec in (E0C_SPEC, E0D_SPEC):
        variant = _build_configuration(spec, "cuda")
        differing = {k for k in base | variant if base.get(k) != variant.get(k)}
        assert differing == {"class_weighting"}
        assert _canonical_hash(variant) != _canonical_hash(base)


# --- environment drift ------------------------------------------------------

BASE_ENVIRONMENT: dict[str, object] = {
    "python": "3.12.13",
    "pytorch": "2.7.0",
    "cuda_runtime": "12.8",
    "cuda_available": True,
    "requested_device": "cuda",
    "cuda_device": {"index": 0, "name": "NVIDIA L40S", "capability": [8, 9]},
    "cublas_workspace_config": ":4096:8",
}


def test_strict_mode_requires_an_exact_environment_match() -> None:
    other = {**BASE_ENVIRONMENT, "python": "3.12.14"}

    assert _environment_matches(BASE_ENVIRONMENT, BASE_ENVIRONMENT, allow_drift=False)
    assert not _environment_matches(other, BASE_ENVIRONMENT, allow_drift=False)


@pytest.mark.parametrize(
    "difference",
    [
        {"python": "3.12.14"},
        {"cuda_device": {"index": 2, "name": "NVIDIA L40S", "capability": [8, 9]}},
        {"cuda_device": {"index": 0, "name": "NVIDIA RTX 4050", "capability": [8, 9]}},
    ],
    ids=["python-patch", "card-index", "card-model"],
)
def test_drift_allows_differences_that_do_not_change_results(
    difference: dict[str, object],
) -> None:
    recorded = {**BASE_ENVIRONMENT, **difference}

    assert _environment_matches(recorded, BASE_ENVIRONMENT, allow_drift=True)


@pytest.mark.parametrize(
    "difference",
    [
        {"pytorch": "2.8.0"},
        {"cuda_runtime": "12.6"},
        {"cublas_workspace_config": None},
        {"requested_device": "cpu"},
    ],
    ids=["pytorch", "cuda-runtime", "cublas", "device-type"],
)
def test_drift_still_rejects_differences_that_change_numerics(
    difference: dict[str, object],
) -> None:
    recorded = {**BASE_ENVIRONMENT, **difference}

    assert not _environment_matches(recorded, BASE_ENVIRONMENT, allow_drift=True)


# --- landmark graph provenance ---------------------------------------------


def _graph_file(path: Path, payload: bytes = b"graph") -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    return path


def test_graph_record_fingerprints_the_file(tmp_path: Path) -> None:
    record = _graph_record(_graph_file(tmp_path / "graph.npz"))

    assert record is not None
    assert record["size_bytes"] == len(b"graph")
    assert isinstance(record["sha256"], str) and len(record["sha256"]) == 64


def test_a_summary_without_inputs_adopts_the_current_ones(tmp_path: Path) -> None:
    """Summaries written before input fingerprinting must stay resumable."""
    inputs = {"landmark_graph": _graph_record(_graph_file(tmp_path / "graph.npz"))}
    summary: dict[str, object] = {"seeds": []}

    _validate_inputs_identity(summary, inputs, E1_SPEC)

    assert summary["inputs"] == inputs


def test_resuming_with_a_different_graph_is_refused(tmp_path: Path) -> None:
    """The graph defines the node topology, so seeds across graphs are not comparable."""
    original = {"landmark_graph": _graph_record(_graph_file(tmp_path / "a.npz", b"one"))}
    summary: dict[str, object] = {"inputs": original}
    changed = {"landmark_graph": _graph_record(_graph_file(tmp_path / "b.npz", b"two"))}

    with pytest.raises(ValueError, match="different landmark_graph"):
        _validate_inputs_identity(summary, changed, E1_SPEC)


def test_unresolved_graph_builder_names_the_fix() -> None:
    with pytest.raises(RuntimeError, match="reproduce_experiment"):
        stgcn_model_builder(None)()


def test_bound_graph_builder_keeps_the_name_metrics_json_records(tmp_path: Path) -> None:
    """train_model writes build_model.__name__; a closure repr would leak a path."""
    builder = stgcn_model_builder(tmp_path / "graph.npz")

    assert builder.__name__ == "build_stgcn_model"
