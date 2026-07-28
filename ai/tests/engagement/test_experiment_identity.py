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
    E0E_SPEC,
    E0F_SPEC,
    E1_SPEC,
    E1A_SPEC,
    E1B_SPEC,
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
    ("E0-E", "cpu"): "3c41d0abab8cf00e5b321976496df47389d85a9251910316019c7c9a9784d436",
    ("E0-E", "cuda"): "c7a6f7eeaf431e0ec0831c756105ac80fd678a65185b2b9261f9e377449d887b",
    ("E0-F", "cpu"): "aa041c0b0abad3f9d89e9e66d8b0ba7896596672230a5f01db23c16cfb4a6810",
    ("E0-F", "cuda"): "32fa6faa58b51f2b1d6ac71ca54605b6a4e1f1910aecac071925b9a270decdca",
    ("E1", "cpu"): "9c6fb102d0b600d04dbd3c6b569a6f06248e5ae35efe603979401e8a4617e13d",
    ("E1", "cuda"): "69a87549d00a41de01eab8d94e97af40b2c6baed5012ecb9350438cd233c989e",
    ("E1-A", "cpu"): "d0419e9b8063ef40b3fd97c15fdf62865bdf7457cc141eb82bde96c0bd31e59e",
    ("E1-A", "cuda"): "5bc0d7f9ae6420c53d3b1a3d107d2a2165b5ee22aa88541a24468051f608d07e",
    ("E1-B", "cpu"): "f97f99b67dbfcd9175eb4ba5a5a6f0d55af19194c914f9425466b9d458406326",
    ("E1-B", "cuda"): "b2f2ddf8514256a654ecb15a83c08a6156aef9838dac7939bb2a7c9bae10ef9e",
}

SPECS_TUPLE: tuple[ExperimentSpec, ...] = (
    E0_SPEC,
    E0A_SPEC,
    E0B_SPEC,
    E0C_SPEC,
    E0D_SPEC,
    E0E_SPEC,
    E0F_SPEC,
    E1_SPEC,
    E1A_SPEC,
    E1B_SPEC,
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


def test_e1a_restores_the_paper_training_conditions() -> None:
    """E1-A differs from E1 only in the three conditions that drifted.

    arXiv:2403.17175 trains with batch 16 at lr 1e-3 for all 300 epochs,
    decaying at 100 and 200. E1 used batch 32 / lr 2e-3 (raised for laptop-GPU
    throughput) and stopped early at patience 20, so no seed ever reached the
    first decay.
    """
    e1 = _build_configuration(E1_SPEC, "cuda")
    e1a = _build_configuration(E1A_SPEC, "cuda")

    differing = {key for key in e1 | e1a if e1.get(key) != e1a.get(key)}
    assert differing == {"learning_rate", "batch_size", "early_stopping"}
    assert e1a["learning_rate"] == 1e-3
    assert e1a["batch_size"] == 16
    assert e1a["maximum_epochs"] == e1["maximum_epochs"] == 300
    assert e1a["lr_step"] == e1["lr_step"] == 100


def test_e1a_early_stopping_cannot_fire_before_the_budget_ends() -> None:
    """patience == max_epochs is how a spec opts out of early stopping."""
    assert E1A_SPEC.patience == E1A_SPEC.max_epochs

    stopping = _build_configuration(E1A_SPEC, "cuda")["early_stopping"]

    assert isinstance(stopping, dict)
    assert stopping["patience"] == E1A_SPEC.max_epochs


def test_e1b_differs_from_e1a_only_in_temporal_resolution() -> None:
    """E1-B is E1-A at the paper's frame rate; nothing else may move.

    The paper feeds all 300 frames of a 10s clip at 30 FPS while we sample 10,
    and its Table 5 attributes 3.1%p to subsampling alone. Isolating that means
    every training condition stays exactly as E1-A set it.
    """
    e1a = _build_configuration(E1A_SPEC, "cuda")
    e1b = _build_configuration(E1B_SPEC, "cuda")

    differing = {key for key in e1a | e1b if e1a.get(key) != e1b.get(key)}
    assert differing == {"representation"}
    assert E1B_SPEC.array_shape == (3, 300, 78)
    assert E1A_SPEC.array_shape == (3, 100, 78)
    for field in ("learning_rate", "batch_size", "max_epochs", "patience", "lr_step"):
        assert getattr(E1B_SPEC, field) == getattr(E1A_SPEC, field)


def test_e1b_reads_its_own_representation() -> None:
    """A 300-step cache must not be mistaken for E1's 100-step one."""
    assert E1B_SPEC.schema_name == "landmark_78_300_v1"
    assert E1B_SPEC.schema_name != E1A_SPEC.schema_name


def test_focal_protocol_isolates_the_loss_against_e0d() -> None:
    """E0-E keeps E0-D's sqrt_balanced weights and changes only the loss shape,
    so the comparison attributes any difference to the focal term alone."""
    base = _build_configuration(E0D_SPEC, "cuda")

    variant = _build_configuration(E0E_SPEC, "cuda")

    differing = {k for k in base | variant if base.get(k) != variant.get(k)}
    assert differing == {"loss", "focal_gamma"}
    assert _canonical_hash(variant) != _canonical_hash(base)


def test_sampler_protocol_isolates_the_sampling_against_e0() -> None:
    """E0-F moves the correction from the loss to the sampler, so it must carry
    E0's unweighted loss and differ from E0 in the sampler alone."""
    base = _build_configuration(E0_SPEC, "cuda")

    variant = _build_configuration(E0F_SPEC, "cuda")

    differing = {k for k in base | variant if base.get(k) != variant.get(k)}
    assert differing == {"sampler"}
    assert _canonical_hash(variant) != _canonical_hash(base)


def test_unweighted_and_unsampled_protocols_record_no_extra_keys() -> None:
    """Adding `loss`/`focal_gamma`/`sampler` unconditionally would have rewritten
    every existing protocol's hash and discarded its completed seeds."""
    for spec in (E0_SPEC, E0A_SPEC, E0C_SPEC, E0D_SPEC, E1_SPEC, E1A_SPEC, E1B_SPEC):
        configuration = _build_configuration(spec, "cuda")

        assert "focal_gamma" not in configuration
        assert "sampler" not in configuration
        assert "loss" not in configuration


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
