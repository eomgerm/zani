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

import hashlib
import json
from dataclasses import asdict, replace
from pathlib import Path
from typing import cast

import pytest

from zani_ai.engagement.experiment import (
    CANDIDATE_SEEDS,
    E0_10_SPEC,
    E0_SEEDS,
    E0_SPEC,
    E0A_SPEC,
    E0B_SPEC,
    E0C_SPEC,
    E0D_SPEC,
    E0E_SPEC,
    E0F_SPEC,
    E0G_SPEC,
    E0H_SPEC,
    E0I_SPEC,
    E0J_SPEC,
    E0K_SPEC,
    E0L_SPEC,
    E1_SPEC,
    E1A_SPEC,
    E1B_SPEC,
    E1P_SPEC,
    SPECS,
    ExperimentSpec,
    _assert_file_record_unchanged,
    _build_configuration,
    _canonical_hash,
    _environment_matches,
    _graph_record,
    _reliability_record,
    _seed_is_complete,
    _stage1_record,
    _validate_inputs_identity,
    stgcn_model_builder,
)
from zani_ai.engagement.reliability import (
    ClipReliability,
    ReliabilityCriteria,
    _json_safe,
    assess_reliability_signal,
    summarize_reliability,
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
    ("E0-G", "cpu"): "5886db84162b0342eb4805c0b1b49ce953b5f10d74dbcf07b750d98bf502c471",
    ("E0-G", "cuda"): "ddf2e00582eaa1c5b7d38220c0622f8a5b6f8acbc042aae9a32fc6d3283ddea8",
    ("E0-H", "cpu"): "1ff748ff0a1148f3c90d2af760690bf0eac13f690958c77100fe1e32553b8c01",
    ("E0-H", "cuda"): "766e2ceb2272162a63756ecd23d3add33c20cf0a99e2a2a09228a313ce13d864",
    ("E0-I", "cpu"): "2b1c6bc1ac3225b60f3df609f00c1e6e67b79f4c2569acd0d4c499c10e337ac9",
    ("E0-I", "cuda"): "0da85a5f7b898984ae7f79bf959b0f604fba1c5177b3a6028fab0eb7b3e656f2",
    ("E0-J", "cpu"): "9f4347ecb7e254743f0583330405bbdc3b9a544ab89c5c7c402c56b19a0e0d43",
    ("E0-J", "cuda"): "1a01ec49bfeddb104a528f0133161b61373fe1cab8cd1962f6adcb3a9dd0b00c",
    ("E0-K", "cpu"): "16789bf6e095421f52cf81a59c3385dfce576f7234011622e1c39290e87fa4ea",
    ("E0-K", "cuda"): "2bdd0769df956cff3f2787e8b5ab5735a48897dda8683f1f625f415b3be8673b",
    ("E0-L", "cpu"): "76c7321e20450759a7d7baa494ef8b43da9316db42779f9e4cefdeaa397c3d8b",
    ("E0-L", "cuda"): "2c6c20aafb133ad95ae44b34f5ccbf17125bb27351009af76b2c337621b6703b",
    # E0-10 is new in S15P11A105-238; measured on this tree, not before it.
    ("E0-10", "cpu"): "bdca954125cd03df0915a62015f457b5e80317c0746c83f4ff7d8d4a7a62816e",
    ("E0-10", "cuda"): "a70522fae9f65c7241f5310600f88ef2f47d39014f204cf94a4b9dc48676b8e1",
    ("E1", "cpu"): "9c6fb102d0b600d04dbd3c6b569a6f06248e5ae35efe603979401e8a4617e13d",
    ("E1", "cuda"): "69a87549d00a41de01eab8d94e97af40b2c6baed5012ecb9350438cd233c989e",
    ("E1-A", "cpu"): "d0419e9b8063ef40b3fd97c15fdf62865bdf7457cc141eb82bde96c0bd31e59e",
    ("E1-A", "cuda"): "5bc0d7f9ae6420c53d3b1a3d107d2a2165b5ee22aa88541a24468051f608d07e",
    ("E1-B", "cpu"): "f97f99b67dbfcd9175eb4ba5a5a6f0d55af19194c914f9425466b9d458406326",
    ("E1-B", "cuda"): "b2f2ddf8514256a654ecb15a83c08a6156aef9838dac7939bb2a7c9bae10ef9e",
    ("E1-P", "cpu"): "c5fb4e569e4c488b369d76527ba0e0426b42445394ad42c52592fb3426863a2f",
    ("E1-P", "cuda"): "75d43a54dd388461861a136106c9e1a7c95644b5d6c6c117f7bee29909aea1c8",
}

SPECS_TUPLE: tuple[ExperimentSpec, ...] = (
    E0_SPEC,
    E0A_SPEC,
    E0B_SPEC,
    E0C_SPEC,
    E0D_SPEC,
    E0E_SPEC,
    E0F_SPEC,
    E0G_SPEC,
    E0H_SPEC,
    E0I_SPEC,
    E0J_SPEC,
    E0K_SPEC,
    E0L_SPEC,
    E0_10_SPEC,
    E1_SPEC,
    E1A_SPEC,
    E1B_SPEC,
    E1P_SPEC,
)


@pytest.mark.parametrize("spec", SPECS_TUPLE, ids=lambda spec: spec.protocol)
@pytest.mark.parametrize("device", ["cpu", "cuda"])
def test_configuration_hash_is_unchanged(spec: ExperimentSpec, device: str) -> None:
    configuration = _build_configuration(spec, device)

    assert _canonical_hash(configuration) == BASELINE_HASHES[(spec.protocol, device)]


@pytest.mark.parametrize("spec", SPECS_TUPLE, ids=lambda spec: spec.protocol)
@pytest.mark.parametrize("device", ["cpu", "cuda"])
def test_configuration_records_only_the_device_type(spec: ExperimentSpec, device: str) -> None:
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


def test_candidate_seeds_extend_the_frozen_five() -> None:
    """S15P11A105-238 raises the count without moving the seeds already run.

    The overlap is what lets a 10-seed run be read against the 5-seed run of
    the same protocol seed by seed; a fresh set of ten would make the two
    incomparable at that level for no gain.
    """
    assert len(CANDIDATE_SEEDS) == 10
    assert CANDIDATE_SEEDS[: len(E0_SEEDS)] == E0_SEEDS
    assert len(set(CANDIDATE_SEEDS)) == len(CANDIDATE_SEEDS)


def test_new_specs_default_to_the_candidate_seed_count() -> None:
    """A protocol added from now on must not silently inherit five seeds."""
    added_without_thinking = ExperimentSpec("E-new", E0_SPEC.schema, E0_SPEC.model_config)

    assert added_without_thinking.seeds == CANDIDATE_SEEDS


@pytest.mark.parametrize("spec", SPECS_TUPLE, ids=lambda spec: spec.protocol)
def test_completed_protocols_stay_pinned_to_the_five_seed_identity(spec: ExperimentSpec) -> None:
    """Everything through E0-L has seeds on disk under the 5-seed hash.

    ``seeds`` is inside ``configuration``, so letting one of these inherit the
    new default would change its ``configuration_sha256`` and make
    ``_validate_summary_identity`` reject its own output directory. Only a
    protocol with nothing on disk yet may carry the new list: E0-10, and E1-P,
    which is added by S15P11A105-288 and has never been run.
    """
    ten_seed_protocols = {"E0-10", "E1-P"}
    expected = CANDIDATE_SEEDS if spec.protocol in ten_seed_protocols else E0_SEEDS

    assert spec.seeds == expected


def test_e0_10_is_e0_measured_more_times_and_nothing_else() -> None:
    """The baseline at ten seeds must differ from E0 in the seed list alone.

    Its whole purpose is to be comparable to E0: any other drift would mean the
    10-seed comparison is measuring the drift instead of the candidate.
    """
    base = _build_configuration(E0_SPEC, "cuda")
    extended = _build_configuration(E0_10_SPEC, "cuda")
    differing = {key for key in base | extended if base.get(key) != extended.get(key)}

    assert differing == {"seeds"}
    assert extended["seeds"] == list(CANDIDATE_SEEDS)
    assert _canonical_hash(extended) != _canonical_hash(base)
    assert replace(E0_10_SPEC, protocol="E0", seeds=E0_SEEDS) == E0_SPEC


def test_e0k_runs_its_whole_schedule_and_differs_from_e0_only_there() -> None:
    """E0-K's point is that the schedule actually runs; a hash cannot show that.

    ``BASELINE_HASHES`` pins the identity but is opaque -- re-pinning it would
    hide a wrong learning rate or a patience that still stops early. These are
    the four values the protocol exists to set, plus the assertion that nothing
    else moved: with ``patience == max_epochs`` no seed can stop before the
    ``lr_step`` decays at epoch 100 and 200.
    """
    assert E0K_SPEC.learning_rate == 1e-3
    assert E0K_SPEC.max_epochs == 300
    assert E0K_SPEC.patience == E0K_SPEC.max_epochs
    assert E0K_SPEC.lr_step == 100
    assert E0K_SPEC.max_epochs // E0K_SPEC.lr_step == 3

    schedule_reverted = replace(
        E0K_SPEC,
        protocol=E0_SPEC.protocol,
        learning_rate=E0_SPEC.learning_rate,
        max_epochs=E0_SPEC.max_epochs,
        patience=E0_SPEC.patience,
        lr_step=E0_SPEC.lr_step,
    )

    assert schedule_reverted == E0_SPEC


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


def test_schedule_alone_separates_e0g_from_e0() -> None:
    """E0-G moves only the training-schedule group: lr, early stopping, decay."""
    base = _build_configuration(E0_SPEC, "cuda")

    variant = _build_configuration(E0G_SPEC, "cuda")

    differing = {k for k in base | variant if base.get(k) != variant.get(k)}
    assert differing == {"learning_rate", "early_stopping", "lr_step"}
    assert _canonical_hash(variant) != _canonical_hash(base)


def test_target_encoding_alone_separates_e0h_from_e0() -> None:
    """E0-H changes the target the loss is fitted to and nothing else.

    It deliberately inherits E0's schedule rather than E0-G's, so lr, early
    stopping and decay must all stay put; ``sord_alpha`` travels with the
    encoding because a different alpha is a different target distribution.
    """
    base = _build_configuration(E0_SPEC, "cuda")

    variant = _build_configuration(E0H_SPEC, "cuda")

    differing = {k for k in base | variant if base.get(k) != variant.get(k)}
    assert differing == {"target_encoding", "sord_alpha"}
    assert variant["target_encoding"] == "sord"
    assert variant["sord_alpha"] == 2.0
    assert _canonical_hash(variant) != _canonical_hash(base)


def test_e0h_keeps_the_head_and_leaves_the_loss_weighting_alone() -> None:
    """SORD acts on the target, so the softmax head and unweighted CE must stay.

    E0-B already tried the grade order by replacing the head and failed; keeping
    ``head``/``loss`` at E0's values is what makes E0-H a different attempt
    rather than a second CORAL.
    """
    assert E0H_SPEC.model_config.to_dict() == E0_SPEC.model_config.to_dict()
    assert E0H_SPEC.loss == "cross_entropy"
    assert E0H_SPEC.class_weighting == "none"
    assert E0H_SPEC.sampler == "none"
    # The schedule stays on E0's, not E0-G's: cheap base first, and E0-G was
    # never established as the better baseline. See the E0H_SPEC comment.
    for field in ("learning_rate", "batch_size", "max_epochs", "patience", "lr_step"):
        assert getattr(E0H_SPEC, field) == getattr(E0_SPEC, field)

    configuration = _build_configuration(E0H_SPEC, "cuda")

    assert "loss" not in configuration
    assert configuration["class_weighting"] is False


def test_curriculum_alone_separates_e0i_from_e0() -> None:
    base = _build_configuration(E0_SPEC, "cuda")

    variant = _build_configuration(E0I_SPEC, "cuda")

    differing = {key for key in base | variant if base.get(key) != variant.get(key)}
    assert differing == {
        "curriculum",
        "reliable_warmup_epochs",
        "ambiguous_target_encoding",
        "ambiguous_neighbor_mass",
    }
    assert variant["curriculum"] == "label_reliability_v1"
    assert variant["reliable_warmup_epochs"] == 10
    assert variant["ambiguous_target_encoding"] == "adjacent_smoothing"
    assert variant["ambiguous_neighbor_mass"] == 0.2


def test_reliability_input_is_fingerprinted_and_must_be_go(tmp_path: Path) -> None:
    path = tmp_path / "reliability.json"

    def record(clip_id: str, split: str, label: int, predictions: tuple[int, ...]):
        return ClipReliability.from_seed_logits(
            clip_id=clip_id,
            split=split,
            label=label,
            seeds=(42, 43, 44, 45, 46),
            seed_logits=tuple(
                tuple(4.0 if column == prediction else -4.0 for column in range(4))
                for prediction in predictions
            ),
        )

    records = [record(f"train-{label}", "train", label, (label,) * 5) for label in range(4)]
    records.append(record("train-ambiguous", "train", 0, (0, 1, 0, 1, 0)))
    for label in range(4):
        records.extend(
            [
                record(f"valid-{label}-a", "valid", label, (label,) * 5),
                record(f"valid-{label}-b", "valid", label, (label,) * 5),
            ]
        )
    records[5] = record("valid-0-a", "valid", 0, (1,) * 5)
    for label in range(4):
        wrong = (label + 1) % 4
        records.append(
            record(f"valid-ambiguous-{label}", "valid", label, (wrong, wrong, label, wrong, wrong))
        )
    criteria = ReliabilityCriteria()
    assessment = assess_reliability_signal(records, criteria=criteria)
    assert assessment.decision == "go"
    payload = _json_safe(
        {
            "schema_version": "label_reliability_v1",
            "decision": assessment.decision,
            "inputs": {"feature_manifest": {"sha256": "feature-hash"}},
            "criteria": asdict(criteria),
            "assessment": asdict(assessment),
            "summary": asdict(summarize_reliability(records)),
            "clips": [asdict(item) for item in records],
        }
    )
    path.write_text(json.dumps(payload), encoding="utf-8")

    record = _reliability_record(path, feature_manifest_sha256="feature-hash")

    assert record == {
        "path": str(path.resolve()),
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "size_bytes": path.stat().st_size,
    }

    payload["decision"] = "no-go"
    path.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(ValueError, match="decision does not match"):
        _reliability_record(path, feature_manifest_sha256="feature-hash")


def test_e0i_seed_completion_rejects_a_different_reliability_input(tmp_path: Path) -> None:
    configuration = _build_configuration(E0I_SPEC, "cpu")
    record: dict[str, object] = {
        "seed": 42,
        "status": "complete",
        "feature_manifest_sha256": "feature-hash",
        "configuration_sha256": _canonical_hash(configuration),
        "inputs": {"label_reliability": {"sha256": "old"}},
        "artifacts": {},
    }

    complete, reason = _seed_is_complete(
        record,
        seed=42,
        output_dir=tmp_path,
        manifest_sha256="feature-hash",
        configuration=configuration,
        inputs={"label_reliability": {"sha256": "new"}},
        spec=E0I_SPEC,
    )

    assert complete is False
    assert "label_reliability" in reason


def test_reliability_file_change_is_rejected_at_training_boundary(tmp_path: Path) -> None:
    path = tmp_path / "reliability.json"
    path.write_text("original", encoding="utf-8")
    record = {
        "path": str(path.resolve()),
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "size_bytes": path.stat().st_size,
    }
    path.write_text("changed", encoding="utf-8")

    with pytest.raises(RuntimeError, match="label_reliability changed before training"):
        _assert_file_record_unchanged(record, "label_reliability", "before training")


def test_e0l_differs_from_e0_only_in_its_head_and_the_two_rules_it_needs() -> None:
    """E0-L replaces the head and freezes the backbone; the schedule stays E0's.

    ``monotonicity`` and ``decoding`` travel with the head because independent
    binary heads leave both questions open, and either answer changes the numbers
    a fixed set of weights produces.
    """
    base = _build_configuration(E0_SPEC, "cuda")

    variant = _build_configuration(E0L_SPEC, "cuda")

    differing = {key for key in base | variant if base.get(key) != variant.get(key)}
    assert differing == {"model", "loss", "monotonicity", "decoding"}
    assert variant["loss"] == "ordinal_binary_bce"
    assert variant["monotonicity"] == "cumulative_min"
    assert variant["decoding"] == "argmax_class_probability"
    for field in ("learning_rate", "batch_size", "max_epochs", "patience", "lr_step"):
        assert getattr(E0L_SPEC, field) == getattr(E0_SPEC, field)
    model = cast(dict[str, object], variant["model"])
    assert model["head"] == "ordinal_binary"
    assert {key: value for key, value in model.items() if key != "head"} == {
        key: value for key, value in cast(dict[str, object], base["model"]).items()
    }


def _stage1_directory(path: Path, payload: bytes = b"stage1") -> Path:
    for seed in E0L_SPEC.seeds:
        checkpoint = path / f"seed-{seed}" / "best.pt"
        checkpoint.parent.mkdir(parents=True, exist_ok=True)
        checkpoint.write_bytes(payload + str(seed).encode())
    return path


def test_stage1_input_is_fingerprinted_per_seed(tmp_path: Path) -> None:
    """Seed n freezes seed n's backbone, so one shared hash would not locate a
    mismatch. The top-level hash covers the map so a resume can still be judged
    by the single-file rule every other input uses."""
    root = _stage1_directory(tmp_path / "e0-clean")

    record = _stage1_record(E0L_SPEC, root)

    assert record is not None
    seeds = cast(dict[str, object], record["seeds"])
    assert set(seeds) == {str(seed) for seed in E0L_SPEC.seeds}
    checkpoint = root / "seed-42" / "best.pt"
    assert seeds["42"] == {
        "path": str(checkpoint.resolve()),
        "sha256": hashlib.sha256(checkpoint.read_bytes()).hexdigest(),
        "size_bytes": checkpoint.stat().st_size,
    }
    assert record["sha256"] == _canonical_hash(seeds)


def test_a_missing_stage1_checkpoint_names_the_file(tmp_path: Path) -> None:
    root = _stage1_directory(tmp_path / "e0-clean")
    (root / "seed-45" / "best.pt").unlink()

    with pytest.raises(FileNotFoundError, match="stage-1 checkpoint not found"):
        _stage1_record(E0L_SPEC, root)


def test_protocols_without_two_stages_record_no_stage1_input(tmp_path: Path) -> None:
    assert _stage1_record(E0_SPEC, tmp_path) is None


def test_e0l_seed_completion_rejects_a_different_stage1(tmp_path: Path) -> None:
    configuration = _build_configuration(E0L_SPEC, "cpu")
    record: dict[str, object] = {
        "seed": 42,
        "status": "complete",
        "feature_manifest_sha256": "feature-hash",
        "configuration_sha256": _canonical_hash(configuration),
        "inputs": {"stage1": {"sha256": "old"}},
        "artifacts": {},
    }

    complete, reason = _seed_is_complete(
        record,
        seed=42,
        output_dir=tmp_path,
        manifest_sha256="feature-hash",
        configuration=configuration,
        inputs={"stage1": {"sha256": "new"}},
        spec=E0L_SPEC,
    )

    assert complete is False
    assert "stage1" in reason


def test_resuming_against_a_different_stage1_is_refused(tmp_path: Path) -> None:
    root = _stage1_directory(tmp_path / "e0-clean")
    recorded = _stage1_record(E0L_SPEC, root)
    summary: dict[str, object] = {"inputs": {"stage1": recorded}}
    _stage1_directory(tmp_path / "e0-clean", payload=b"retrained")

    with pytest.raises(ValueError, match="used a different stage1"):
        _validate_inputs_identity(
            summary, {"stage1": _stage1_record(E0L_SPEC, root)}, E0L_SPEC
        )


def test_target_encoding_enters_the_identity_only_when_it_leaves_one_hot() -> None:
    """Recording it unconditionally would rewrite every earlier protocol's hash."""
    for spec in SPECS_TUPLE:
        configuration = _build_configuration(spec, "cuda")

        if spec.target_encoding == "one_hot":
            assert "target_encoding" not in configuration
            assert "sord_alpha" not in configuration
        else:
            assert configuration["target_encoding"] == spec.target_encoding


def test_training_schedule_fields_enter_the_identity_only_when_set() -> None:
    """patience and lr_step enter the identity, but a default spec stays as it was."""
    tuned = replace(E0_SPEC, protocol="E0-tuned", patience=200, lr_step=100)

    configuration = _build_configuration(tuned, "cuda")
    base = _build_configuration(E0_SPEC, "cuda")

    early_stopping = configuration["early_stopping"]
    assert isinstance(early_stopping, dict) and early_stopping["patience"] == 200
    assert configuration["lr_step"] == 100
    assert "lr_step" not in base


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
