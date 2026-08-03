from __future__ import annotations

import hashlib
import json
import os
import platform
import statistics
from collections.abc import Callable, Sequence
from dataclasses import dataclass, replace
from pathlib import Path
from typing import cast

# cuBLAS requires this workspace setting for deterministic CUDA matrix multiplication.
# It must be present before the first CUDA operation in this process.
os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")

import torch
from torch import nn

from zani_ai.engagement.export import DeploymentMetadata, export_onnx
from zani_ai.engagement.features import (
    SCHEMA_98,
    SCHEMA_98_PLACEHOLDER,
    SCHEMA_132,
    FeatureSchema,
)
from zani_ai.engagement.landmark_graph import GRAPH_VERSION, load_graph
from zani_ai.engagement.locking import DirectoryLock
from zani_ai.engagement.model import ModelConfig
from zani_ai.engagement.representations import landmark_sequence_name
from zani_ai.engagement.runtime import device_type, parse_device, resolve_landmark_graph
from zani_ai.engagement.stgcn import EngagementSTGCN, STGCNConfig
from zani_ai.engagement.training import (
    EvaluationMetrics,
    FeatureStatistics,
    TrainingConfig,
    load_checkpoint,
    train_model,
    validate_manifest_completion,
)

E0_SEEDS = (42, 43, 44, 45, 46)
_SPLITS = {"train", "valid", "test"}
_CUBLAS_CONFIGS = {":4096:8", ":16:8"}

#: Environment keys that still must match exactly under
#: ``allow_environment_drift``. These change the numerics; the rest (Python
#: patch level, which physical card was allocated) only change the label.
_NUMERIC_ENVIRONMENT_KEYS = (
    "pytorch",
    "cuda_runtime",
    "cublas_workspace_config",
    "requested_device",
)


@dataclass(frozen=True, slots=True)
class E0ExperimentResult:
    summary_path: Path
    completed_seeds: tuple[int, ...]


@dataclass(frozen=True, slots=True)
class ExperimentSpec:
    """Fully describes a reproducible experiment protocol (e.g. E0, E0-A, E1).

    ``schema`` is a token-based ``FeatureSchema`` for the Transformer family
    (E0/E0-A/E0-B); non-token representations (e.g. E1's ST-GCN landmark
    sequences) have no ``FeatureSchema`` and pass ``schema=None`` together
    with ``representation_name`` instead. Use :attr:`schema_name` to get the
    manifest/summary schema string regardless of which one is set.

    The fields below ``seeds`` are all optional and default to the existing
    Transformer behavior, so E0/E0-A/E0-B (which only pass ``protocol``,
    ``schema`` and ``model_config``) are completely unaffected.

    None of these fields may leak into ``_build_configuration``: that dict is
    the reproducibility identity, and ``graph_path`` in particular is a
    per-machine location, not part of the protocol.
    """

    protocol: str
    schema: FeatureSchema | None
    model_config: ModelConfig | STGCNConfig
    seeds: tuple[int, ...] = E0_SEEDS
    # E1 (non-Transformer) hooks; None/defaults reproduce the Transformer path.
    representation_name: str | None = None
    build_model: Callable[..., nn.Module] | None = None
    needs_feature_stats: bool = True
    learning_rate: float = 1e-4
    batch_size: int = 32
    max_epochs: int = 200
    # Early-stopping window. Set it to `max_epochs` to disable stopping and run
    # the full budget, which is what a protocol needs when its learning-rate
    # schedule only pays off late (E1's decay lands at epoch 100 and 200).
    patience: int = 20
    lr_step: int | None = None
    array_key: str = "tokens"
    array_shape: tuple[int, ...] | None = None
    # Loss weighting; see training.CLASS_WEIGHTING_SCHEMES. Part of the
    # protocol identity, so changing it defines a new experiment.
    class_weighting: str = "none"
    # Loss shape and training-split sampling; see training.LOSS_SCHEMES and
    # training.SAMPLER_SCHEMES. Also part of the identity.
    loss: str = "cross_entropy"
    focal_gamma: float = 2.0
    sampler: str = "none"
    # Target encoding for the softmax head; see training.TARGET_ENCODINGS.
    # ``sord_alpha`` decides how much probability reaches the neighbouring
    # grades, so it defines the protocol as much as the encoding name does and
    # both enter the identity together.
    target_encoding: str = "one_hot"
    sord_alpha: float = 2.0
    # E0-I two-stage curriculum. Defaults preserve all earlier identities.
    curriculum: str = "none"
    reliable_warmup_epochs: int = 10
    ambiguous_target_encoding: str = "adjacent_smoothing"
    ambiguous_neighbor_mass: float = 0.2
    needs_reliability_manifest: bool = False
    reliability_manifest: Path | None = None
    # E0-L two-stage training. ``stage1_output`` is a completed protocol's output
    # directory; seed ``n`` freezes the backbone of ``<dir>/seed-<n>/best.pt``.
    # Like ``graph_path`` it is a per-machine location and must never enter
    # ``_build_configuration`` -- the checkpoint fingerprints go in ``inputs``.
    needs_stage1_checkpoint: bool = False
    stage1_output: Path | None = None
    # ST-GCN specs read a landmark graph file whose location varies per machine.
    # ``reproduce_experiment`` resolves it and rebinds ``build_model``.
    needs_landmark_graph: bool = False
    graph_path: Path | None = None

    @property
    def schema_name(self) -> str:
        """The manifest/summary schema name, for token and non-token specs alike."""
        if self.representation_name is not None:
            return self.representation_name
        if self.schema is None:
            raise ValueError(f"{self.protocol} spec has neither schema nor representation_name")
        return self.schema.name


E0_SPEC = ExperimentSpec("E0", SCHEMA_98, ModelConfig(input_dim=98))
E0A_SPEC = ExperimentSpec("E0-A", SCHEMA_132, ModelConfig(input_dim=132))
E0B_SPEC = ExperimentSpec("E0-B", SCHEMA_98, ModelConfig(input_dim=98, head="coral"))

# E0-C / E0-D vary only the loss weighting against E0. E0's errors are 89%
# adjacent-class and its boundaries sit against the majority class
# (Highly-Engaged is 56% of validation), so the loss -- not the encoder --
# is what the evidence points at. E0-D softens E0-C in case full inversion
# overcorrects: with these counts `balanced` spans ~7.7x and
# `sqrt_balanced` ~2.8x between the largest and smallest weight.
E0C_SPEC = ExperimentSpec("E0-C", SCHEMA_98, ModelConfig(input_dim=98), class_weighting="balanced")
E0D_SPEC = ExperimentSpec(
    "E0-D", SCHEMA_98, ModelConfig(input_dim=98), class_weighting="sqrt_balanced"
)

# E0-E / E0-F pick up where E0-D stopped: it beat E0 by only +0.49%p Validation
# Macro F1, short of the +1.0%p bar. Both remaining levers act on the imbalance
# without touching the encoder, and each isolates one variable.
#
# E0-E keeps E0-D's sqrt_balanced weights and changes only the loss shape, so
# any difference is the focal term alone. Frequency weighting cannot tell an
# easy majority sample from a hard one; `(1 - p_t)^2` can, which matters here
# because 89% of E0's errors are one adjacent grade away.
#
# E0-F moves the correction from the loss to the batch and therefore reverts to
# E0's unweighted loss -- keeping sqrt_balanced weights on top of a balanced
# sampler would correct the same imbalance twice.
E0E_SPEC = ExperimentSpec(
    "E0-E",
    SCHEMA_98,
    ModelConfig(input_dim=98),
    class_weighting="sqrt_balanced",
    loss="focal",
    focal_gamma=2.0,
)
E0F_SPEC = ExperimentSpec("E0-F", SCHEMA_98, ModelConfig(input_dim=98), sampler="balanced")

# E0-G is the baseline reset after E0-C..E0-F all died of the same cause: with
# patience 20 their best_epoch landed at 0-4, so no loss or sampler change had
# the epochs it needed to reshape a decision boundary. Applying an oracle logit
# adjustment (thresholds chosen on Test itself, a cheating upper bound) to the
# saved logits bought at most ~+0.4%p, which says the logits themselves have to
# change, not the decision rule on top of them.
#
# Following the E1-A precedent, the training schedule moves as one variable
# group: slow the optimizer 10x, disable early stopping, and add the decay
# stage E0 never survived long enough to reach.
E0G_SPEC = ExperimentSpec(
    "E0-G",
    SCHEMA_98,
    ModelConfig(input_dim=98),
    learning_rate=1e-5,
    patience=200,
    lr_step=100,
)


# E0-H attacks the adjacent-grade confusion that survived every correction so
# far: Test errors stayed 79.2-80.4% one grade away across E0..E0-F, and an
# oracle logit adjustment measured on Test itself bought at most +0.4%p, so the
# decision rule is not what is wrong -- the target the loss is fitted to is.
#
# SORD (Diaz & Marathe, CVPR 2019) replaces the one-hot target with
# `target_j ∝ exp(-alpha (i - j)^2)`, leaving probability on the neighbouring
# grades. Where E0-B also used the grade order and failed (macro-F1 0.5185), it
# swapped the softmax head for cumulative threshold logits; SORD keeps the head
# and the argmax decoding untouched, so its failure mode is a different one.
# Label subjectivity points the same way: annotators agree exactly 46.25% of the
# time but within one grade 88.75%, which a neighbour-weighted target describes
# better than a one-hot one.
#
# It is built on E0, not on E0-G, which ticket 210 had pre-registered as the
# base. Three things moved that decision, and none of them is "E0-G failed":
#
# * Cost asymmetry. E0-G disables early stopping, so it runs 200 epochs x 5
#   seeds = 1000; E0 stops at best_epoch + 20, which its measured best_epochs
#   [1, 3, 1, 2, 9] put at 116. Trying the cheap base first costs +12% if it
#   fails and saves 88% if it does not.
# * Comparison family. SORD changes the loss target, the same axis as E0-C/E0-D
#   (weights) and E0-E (focal). On E0 it joins that four-way comparison; on
#   E0-G it would only be comparable to E0-G.
# * E0-G is not established as the better baseline: all three metrics came back
#   statistically indistinguishable from E0 (Welch t = +0.40 / -0.92 / -1.42,
#   n = 5 + 5), so promoting it would rest on nothing measured.
#
# What E0-G ruled out is the schedule's *main effect* -- it changed the schedule
# under a plain CE loss. "A changed loss needs more epochs to pay off" is an
# interaction and remains untested; if E0-H fails here, re-running it on E0-G's
# schedule is exactly that test, so this ordering defers the question instead of
# discarding it.
#
# So the schedule stays at E0's lr 1e-4 / patience 20 / no decay, the target
# encoding is the single variable, and the comparison is against E0 directly.
# Class weighting stays off -- see SordObjective, whose spread target has no
# hard label for a per-class weight to act on.
E0H_SPEC = ExperimentSpec(
    "E0-H",
    SCHEMA_98,
    ModelConfig(input_dim=98),
    target_encoding="sord",
    sord_alpha=2.0,
)

E0I_SPEC = ExperimentSpec(
    "E0-I",
    SCHEMA_98,
    ModelConfig(input_dim=98),
    curriculum="label_reliability_v1",
    reliable_warmup_epochs=10,
    ambiguous_target_encoding="adjacent_smoothing",
    ambiguous_neighbor_mass=0.2,
    needs_reliability_manifest=True,
)

# PriorNet (arXiv:2605.03615) finds its largest single-component EngageNet
# gain by retaining failed face detections as fixed zero-frame placeholders.
# E0-J isolates that preprocessing prior: model, objective, schedule, seeds,
# and tensor shape stay identical to E0; only representation semantics change.
E0J_SPEC = ExperimentSpec(
    "E0-J",
    SCHEMA_98_PLACEHOLDER,
    ModelConfig(input_dim=98),
)

# E0-K is the second attempt at the schedule, and it moves the learning rate the
# way E0-G should have. The pathology is unchanged since E0-C: under patience 20
# the family's `best_epoch` lands at 0-11, so nothing in it has ever trained --
# every loss, sampler and target change was measured on a model that stopped
# before it could reshape a boundary. E0-G reached for that problem and went the
# wrong direction, dropping lr to 1e-5 (10x slower, not faster); its Validation
# 66.93% against E0's 66.65% was statistically indistinguishable on all three
# metrics, which leaves the *upward* half of the lr axis untested.
#
# There is no Transformer-family literature schedule to restore here. EngageNet
# (arXiv:2302.00431), which E0 adapts, publishes only layers, units, activations
# and dropout in its Table 2 -- no optimizer, learning rate, batch size or epoch
# count anywhere in the paper. What it does give is the target: Table 4 reports
# 69.10% Validation / 67.61% Test for the Gaze + Head Pose + AU Transformer,
# against our 66.65%.
#
# So lr 1e-3 / 300 epochs / decay every 100 is borrowed from arXiv:2403.17175 --
# the ST-GCN paper E1 reproduces, a different architecture family. It enters as a
# prescription for the measured pathology, not as reproduction, and that is also
# why `batch_size` stays at E0's 32 rather than following that paper to 16: a
# batch size chosen for a graph convolution says nothing about this Transformer,
# and holding it fixed puts E0 (1e-4), E0-G (1e-5) and E0-K (1e-3) on one lr axis
# where the three are directly comparable.
#
# `patience == max_epochs` disables early stopping, as on E1-A. With `lr_step`
# 100 over 300 epochs the decay fires at 100 and 200, so unlike E0-G -- whose
# 200-epoch budget left room for one decay -- the full recipe actually runs.
E0K_SPEC = ExperimentSpec(
    "E0-K",
    SCHEMA_98,
    ModelConfig(input_dim=98),
    learning_rate=1e-3,
    max_epochs=300,
    patience=300,
    lr_step=100,
)


# E0-L takes the grade order apart into K-1 = 3 *independent* binary decisions,
# which is the decomposition the literature reports as 69.37% -> 71.24% on
# ST-GCN. Both of our earlier ordinal attempts differ from it:
#
# * E0-B (CORAL) lets one weight vector serve all three thresholds and varies
#   only the bias, so its cumulative logits are monotone by construction and
#   there is nothing for independent heads to disagree about. macro-F1 0.5185.
# * E0-H (SORD) keeps the softmax head and spreads the *target* over the
#   neighbouring grades instead. Validation accuracy 67.58% (our best) against
#   macro-F1 56.80% (our worst).
#
# So the untested combination is independence plus a frozen backbone, and this
# spec changes nothing else: stage 2 inherits E0's lr 1e-4 / 200 epochs /
# patience 20, and the difference against E0 is the head and the freeze.
#
# Stage 1 is not retrained. `--stage1` points at E0's completed output and seed
# n reuses seed n's checkpoint, following E0-I's precedent of fingerprinting a
# prior protocol's artifact into `inputs`. Retraining it would burn E0's ~116
# measured epochs to arrive at the same weights.
#
# `monotonicity` and `decoding` enter the identity because both change the
# numbers a fixed set of weights produces: the running minimum decides what the
# class probabilities are, and argmax over them decides which grade is read out
# (CORAL's `count(p > 0.5)` would answer differently on the same vector).
E0L_SPEC = ExperimentSpec(
    "E0-L",
    SCHEMA_98,
    ModelConfig(input_dim=98, head="ordinal_binary"),
    needs_stage1_checkpoint=True,
)


def stgcn_model_builder(graph_path: Path | None) -> Callable[..., nn.Module]:
    """Build an E1 ``TrainingConfig.build_model`` bound to a resolved graph file.

    The inner function is deliberately named ``build_stgcn_model``:
    ``training.train_model`` records ``build_model.__name__`` in
    ``metrics.json``, so an anonymous closure would write a machine-specific
    ``repr`` (absolute paths included) into every run's metrics.

    ``graph_path`` is ``None`` only on the module-level spec, which exists
    before any features root is known; ``reproduce_experiment`` rebinds it.
    """

    def build_stgcn_model(statistics: FeatureStatistics | None = None) -> nn.Module:
        """Ignores ``statistics``: ST-GCN needs no feature normalization stats.

        See ``ExperimentSpec.needs_feature_stats=False`` on ``E1_SPEC``.
        """
        del statistics
        if graph_path is None:
            raise RuntimeError(
                "E1 landmark graph is unresolved; run through reproduce_experiment "
                "or set ExperimentSpec.graph_path"
            )
        _, partitions = load_graph(graph_path)
        return EngagementSTGCN(torch.as_tensor(partitions), STGCNConfig())

    return build_stgcn_model


E1_SPEC = ExperimentSpec(
    "E1",
    None,
    STGCNConfig(),
    seeds=E0_SEEDS,
    representation_name=GRAPH_VERSION,
    build_model=stgcn_model_builder(None),
    needs_feature_stats=False,
    learning_rate=2e-3,
    batch_size=32,
    max_epochs=300,
    lr_step=100,
    array_key="sequence",
    array_shape=(3, 100, 78),
    needs_landmark_graph=True,
)


# E1-A restores the training conditions of the paper E1 reproduces
# (arXiv:2403.17175), which E1 had drifted from.
#
# * batch 16 / lr 1e-3 are the paper's values, and were also this experiment's
#   ticketed plan. Commits bcaeda2 and 4e146e1 raised them to 32 / 2e-3 for
#   throughput on a 6GB laptop GPU -- a constraint the L40S removed.
# * `patience == max_epochs` disables early stopping. The paper trains all 300
#   epochs and decays the learning rate at 100 and 200; E1's measured
#   best_epochs were 44, 48, 13, 8 and 25, so no seed ever reached the first
#   decay and half the recipe never ran.
#
# The input is still 100 frames at 10 FPS against the paper's 300 at 30 FPS --
# that needs a new representation and is tracked separately.
E1A_SPEC = ExperimentSpec(
    "E1-A",
    None,
    STGCNConfig(),
    seeds=E0_SEEDS,
    representation_name=GRAPH_VERSION,
    build_model=stgcn_model_builder(None),
    needs_feature_stats=False,
    learning_rate=1e-3,
    batch_size=16,
    max_epochs=300,
    patience=300,
    lr_step=100,
    array_key="sequence",
    array_shape=(3, 100, 78),
    needs_landmark_graph=True,
)


# E1-B adds the paper's temporal resolution on top of E1-A. The paper feeds all
# 300 frames of a 10s clip at 30 FPS; we sample 10 FPS, keeping one frame in
# three. Its Table 5 reports 0.7124 -> 0.6813 from subsampling every 2nd frame
# alone, so this is the largest remaining difference and the last untested one.
#
# It needs its own raw cache: SAMPLE_FPS discards frames during extraction, so
# `raw_frames_v1` physically cannot supply 300 steps and the source videos must
# be re-extracted at 30 FPS.
E1B_SPEC = ExperimentSpec(
    "E1-B",
    None,
    STGCNConfig(),
    seeds=E0_SEEDS,
    representation_name=landmark_sequence_name(300),
    build_model=stgcn_model_builder(None),
    needs_feature_stats=False,
    learning_rate=1e-3,
    batch_size=16,
    max_epochs=300,
    patience=300,
    lr_step=100,
    array_key="sequence",
    array_shape=(3, 300, 78),
    needs_landmark_graph=True,
)


#: Every reproducible protocol, keyed by the name it is known by on the CLI
#: and in ``summary.json``. Lets callers dispatch on the protocol string
#: instead of duplicating a handler per experiment.
SPECS: dict[str, ExperimentSpec] = {
    spec.protocol: spec
    for spec in (
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
    )
}


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _canonical_hash(payload: dict[str, object]) -> str:
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode(
        "utf-8"
    )
    return hashlib.sha256(encoded).hexdigest()


def _write_json_atomic(path: Path, payload: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    # The PID keeps concurrent seed processes from sharing a scratch file.
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    try:
        temporary.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def _validate_manifest(features_root: Path, spec: ExperimentSpec) -> tuple[Path, str]:
    manifest_path = features_root / "manifest.json"
    if not manifest_path.is_file():
        raise FileNotFoundError(f"feature manifest not found: {manifest_path}")
    try:
        manifest_bytes = manifest_path.read_bytes()
        payload = json.loads(manifest_bytes.decode("utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise ValueError(f"invalid feature manifest JSON: {manifest_path}") from error
    if not isinstance(payload, dict):
        raise ValueError("feature manifest must be a JSON object")
    if payload.get("schema") != spec.schema_name:
        raise ValueError(f"feature manifest schema must be {spec.schema_name}")
    included, excluded = validate_manifest_completion(payload)
    splits: set[str] = set()
    for index, item in enumerate(included):
        if not isinstance(item, dict) or not isinstance(item.get("split"), str):
            raise ValueError(f"invalid included entry at index {index}")
        split = cast(str, item["split"])
        if split not in _SPLITS:
            raise ValueError(f"unknown feature split: {split}")
        if not isinstance(item.get("clip_id"), str) or not item["clip_id"]:
            raise ValueError(f"invalid clip_id in included entry at index {index}")
        if split != "test":
            label_index = item.get("label_index")
            if not isinstance(label_index, int) or isinstance(label_index, bool):
                raise ValueError(f"invalid label_index in included entry at index {index}")
            if label_index not in range(4):
                raise ValueError(f"label_index out of range in included entry at index {index}")
        feature_path_value = item.get("feature_path")
        if not isinstance(feature_path_value, str) or not feature_path_value:
            raise ValueError(f"invalid feature_path in included entry at index {index}")
        feature_path = features_root / feature_path_value
        try:
            feature_path.resolve().relative_to(features_root.resolve())
        except ValueError as error:
            raise ValueError(
                f"feature_path escapes the feature root in included entry at index {index}"
            ) from error
        if not feature_path.is_file():
            raise FileNotFoundError(f"cached feature not found: {feature_path}")
        fingerprint = item.get("source_fingerprint")
        if not isinstance(fingerprint, str) or not fingerprint:
            raise ValueError(f"invalid source_fingerprint in included entry at index {index}")
        splits.add(split)
    for index, item in enumerate(excluded):
        if not isinstance(item, dict):
            raise ValueError(f"invalid excluded entry at index {index}")
        if item.get("split") not in _SPLITS:
            raise ValueError(f"invalid split in excluded entry at index {index}")
        if not isinstance(item.get("clip_id"), str) or not item["clip_id"]:
            raise ValueError(f"invalid clip_id in excluded entry at index {index}")
        if not isinstance(item.get("reason"), str) or not item["reason"]:
            raise ValueError(f"invalid reason in excluded entry at index {index}")
    if splits != _SPLITS:
        missing = ", ".join(sorted(_SPLITS - splits))
        raise ValueError(f"feature manifest is missing non-empty split(s): {missing}")
    return manifest_path, hashlib.sha256(manifest_bytes).hexdigest()


def _class_weighting_value(spec: ExperimentSpec) -> object:
    """The ``class_weighting`` entry of the configuration dict.

    Unweighted protocols keep the literal ``False`` this field has always
    held, so E0/E0-A/E0-B/E1 hash exactly as before; weighted ones record the
    scheme name, which gives them a distinct identity.
    """
    return False if spec.class_weighting == "none" else spec.class_weighting


def _apply_loss_and_sampling(
    configuration: dict[str, object], spec: ExperimentSpec
) -> dict[str, object]:
    """Record the loss shape, target encoding and sampler, but only when they
    leave the default.

    Emitting these keys unconditionally would rewrite the hash of every protocol
    that predates them and discard its completed seeds, so a plain
    cross-entropy, one-hot, unsampled spec must produce the dict it always has.
    """
    head = getattr(spec.model_config, "head", "softmax")
    if head == "coral":
        # CORAL replaces the head, so its loss is fixed regardless of spec.loss.
        configuration["loss"] = "coral_bce"
    elif head == "ordinal_binary":
        # Same: the head fixes the objective. The two rules below decide what
        # the K-1 head outputs mean, so they belong to the protocol rather than
        # to the reader of its checkpoints.
        configuration["loss"] = "ordinal_binary_bce"
        configuration["monotonicity"] = "cumulative_min"
        configuration["decoding"] = "argmax_class_probability"
    elif spec.loss != "cross_entropy":
        configuration["loss"] = spec.loss
        configuration["focal_gamma"] = spec.focal_gamma
    if spec.sampler != "none":
        configuration["sampler"] = spec.sampler
    if spec.target_encoding != "one_hot":
        configuration["target_encoding"] = spec.target_encoding
        configuration["sord_alpha"] = spec.sord_alpha
    if spec.curriculum != "none":
        configuration["curriculum"] = spec.curriculum
        configuration["reliable_warmup_epochs"] = spec.reliable_warmup_epochs
        configuration["ambiguous_target_encoding"] = spec.ambiguous_target_encoding
        configuration["ambiguous_neighbor_mass"] = spec.ambiguous_neighbor_mass
    return configuration


def _build_configuration(spec: ExperimentSpec, device: str) -> dict[str, object]:
    # Only the device *type* may enter the identity: `cuda` and `cuda:2` are
    # the same protocol on different cards and must share a hash, or moving a
    # run between GPUs would discard every completed seed.
    device = device_type(device)
    if spec.build_model is None:
        # Transformer path (E0/E0-A/E0-B). `spec.learning_rate`/`batch_size`/
        # `max_epochs` default to the exact literals this used to hardcode, so
        # this dict (and its hash) is byte-identical to before for those specs.
        assert spec.schema is not None
        configuration: dict[str, object] = {
            "feature_schema": spec.schema.name,
            "input_shape": ["batch", 20, spec.schema.token_feature_count],
            "seeds": list(spec.seeds),
            "optimizer": "Adam",
            "learning_rate": spec.learning_rate,
            "batch_size": spec.batch_size,
            "maximum_epochs": spec.max_epochs,
            "early_stopping": {
                "metric": "validation_macro_f1",
                "mode": "max",
                "patience": spec.patience,
            },
            "class_weighting": _class_weighting_value(spec),
            "model": {
                **spec.model_config.to_dict(),
                "learned_position_count": 20,
                "pooling": "max",
                "classifier_dimensions": [256, 128, 4],
            },
            "device": device,
            "deterministic_algorithms": True,
            "num_workers": 0,
        }
        # `lr_step` originated on the ST-GCN path, so recording it
        # unconditionally would rewrite the hash of every existing Transformer
        # protocol and discard its completed seeds. Only specs that actually
        # run a decay schedule record it.
        if spec.lr_step is not None:
            configuration["lr_step"] = spec.lr_step
        return _apply_loss_and_sampling(configuration, spec)

    # ST-GCN path (E1 and other non-token representations).
    return _apply_loss_and_sampling(
        {
            "representation": spec.schema_name,
            "model_family": "stgcn",
            "seeds": list(spec.seeds),
            "optimizer": "Adam",
            "learning_rate": spec.learning_rate,
            "batch_size": spec.batch_size,
            "maximum_epochs": spec.max_epochs,
            "lr_step": spec.lr_step,
            "early_stopping": {
                "metric": "validation_macro_f1",
                "mode": "max",
                "patience": spec.patience,
            },
            "class_weighting": _class_weighting_value(spec),
            "model": spec.model_config.to_dict(),
            "device": device,
            "deterministic_algorithms": True,
            "num_workers": 0,
        },
        spec,
    )


def _environment(device: str, spec: ExperimentSpec) -> dict[str, object]:
    kind, requested_index = parse_device(device)
    cuda_available = torch.cuda.is_available()
    if kind == "cuda" and not cuda_available:
        raise RuntimeError(
            f"{spec.protocol} requested device={device}, but PyTorch reports CUDA unavailable"
        )
    cuda_device: dict[str, object] | None = None
    if kind == "cuda":
        # An unqualified `cuda` follows PyTorch's current device, which is what
        # CUDA_VISIBLE_DEVICES already narrows for us on a shared server.
        index = torch.cuda.current_device() if requested_index is None else requested_index
        if not 0 <= index < torch.cuda.device_count():
            raise RuntimeError(
                f"{spec.protocol} requested device={device}, but only "
                f"{torch.cuda.device_count()} CUDA device(s) are visible; "
                "check CUDA_VISIBLE_DEVICES"
            )
        cuda_device = {
            "index": index,
            "name": torch.cuda.get_device_name(index),
            "capability": list(torch.cuda.get_device_capability(index)),
        }
    return {
        "python": platform.python_version(),
        "pytorch": str(torch.__version__),
        "cuda_runtime": torch.version.cuda,
        "cuda_available": cuda_available,
        # Normalized so a card index shows up once, in `cuda_device.index`.
        "requested_device": kind,
        "cuda_device": cuda_device,
        "cublas_workspace_config": os.environ.get("CUBLAS_WORKSPACE_CONFIG"),
    }


def _enable_strict_determinism(device: str) -> None:
    cublas_config = os.environ.get("CUBLAS_WORKSPACE_CONFIG")
    if device_type(device) == "cuda" and cublas_config not in _CUBLAS_CONFIGS:
        raise RuntimeError(
            "strict CUDA determinism requires CUBLAS_WORKSPACE_CONFIG=:4096:8 or :16:8"
        )
    try:
        torch.backends.cudnn.benchmark = False
        torch.backends.cudnn.deterministic = True
        torch.use_deterministic_algorithms(True, warn_only=False)
    except Exception as error:
        raise RuntimeError("PyTorch strict deterministic execution is unsupported") from error
    if not torch.are_deterministic_algorithms_enabled():
        raise RuntimeError("PyTorch strict deterministic execution could not be enabled")


def _graph_record(graph_path: Path | None) -> dict[str, object] | None:
    """Provenance for the landmark graph, which nothing else in the repo records.

    The graph defines the ST-GCN node topology, so two runs that used different
    graphs are not comparable even with an identical ``configuration``. There
    is no CLI that regenerates it, which makes the fingerprint the only link
    between a summary and the file it was trained against.
    """
    if graph_path is None:
        return None
    return {
        "path": str(graph_path.resolve()),
        "sha256": _sha256(graph_path),
        "size_bytes": graph_path.stat().st_size,
    }


def _reliability_record(
    reliability_path: Path | None, *, feature_manifest_sha256: str
) -> dict[str, object] | None:
    if reliability_path is None:
        return None
    path = reliability_path.resolve()
    if not path.is_file():
        raise FileNotFoundError(f"reliability manifest not found: {path}")
    from zani_ai.engagement.reliability import load_validated_reliability_manifest

    validated = load_validated_reliability_manifest(
        path,
        feature_manifest_sha256=feature_manifest_sha256,
        require_go=True,
    )
    size_bytes = path.stat().st_size
    if _sha256(path) != validated.sha256:
        raise RuntimeError(f"reliability manifest changed during validation: {path}")
    return {
        "path": str(path),
        "sha256": validated.sha256,
        "size_bytes": size_bytes,
    }


def _stage1_record(
    spec: ExperimentSpec, stage1_output: Path | None
) -> dict[str, object] | None:
    """Provenance for the stage-1 checkpoints whose backbone stage 2 freezes.

    Every seed gets its own record, because seed ``n`` freezes seed ``n``'s
    backbone and a mismatch there is not a shared-input error but a per-seed one.
    The top-level ``sha256`` is a canonical hash over that map, which is what
    lets :func:`_validate_inputs_identity` -- written against single-file inputs
    -- reject a resume that points at a different stage 1.
    """
    if not spec.needs_stage1_checkpoint:
        return None
    if stage1_output is None:
        raise ValueError(f"{spec.protocol} requires a stage-1 output directory")
    root = stage1_output.resolve()
    seeds: dict[str, object] = {}
    for seed in spec.seeds:
        path = root / f"seed-{seed}" / "best.pt"
        if not path.is_file():
            raise FileNotFoundError(f"stage-1 checkpoint not found: {path}")
        seeds[str(seed)] = {
            "path": str(path),
            "sha256": _sha256(path),
            "size_bytes": path.stat().st_size,
        }
    return {
        "protocol_output": str(root),
        "seeds": seeds,
        "sha256": _canonical_hash(seeds),
    }


def _assert_file_record_unchanged(record: dict[str, object], name: str, boundary: str) -> None:
    path_value = record.get("path")
    expected_hash = record.get("sha256")
    expected_size = record.get("size_bytes")
    if (
        not isinstance(path_value, str)
        or not isinstance(expected_hash, str)
        or not isinstance(expected_size, int)
        or isinstance(expected_size, bool)
    ):
        raise RuntimeError(f"{name} integrity record is invalid")
    path = Path(path_value)
    if not path.is_file() or path.stat().st_size != expected_size or _sha256(path) != expected_hash:
        raise RuntimeError(f"{name} changed {boundary}")


def _validate_inputs_identity(
    summary: dict[str, object], inputs: dict[str, object], spec: ExperimentSpec
) -> None:
    recorded = summary.get("inputs")
    if not isinstance(recorded, dict):
        # Summary predates input fingerprinting; adopt the current one.
        summary["inputs"] = inputs
        return
    for name, current in inputs.items():
        previous = recorded.get(name)
        if previous is None:
            continue
        if (
            isinstance(previous, dict)
            and isinstance(current, dict)
            and previous.get("sha256") != current.get("sha256")
        ):
            raise ValueError(
                f"existing {spec.protocol} summary used a different {name}; "
                "choose a new output directory"
            )
    summary["inputs"] = {**recorded, **inputs}


def _empty_summary(
    manifest_path: Path,
    manifest_sha256: str,
    configuration: dict[str, object],
    environment: dict[str, object],
    spec: ExperimentSpec,
    inputs: dict[str, object],
) -> dict[str, object]:
    return {
        "protocol": spec.protocol,
        "status": "in_progress",
        "feature_manifest": {
            "path": str(manifest_path.resolve()),
            "schema": spec.schema_name,
            "sha256": manifest_sha256,
        },
        "inputs": inputs,
        "configuration": configuration,
        "configuration_sha256": _canonical_hash(configuration),
        "environment": environment,
        "test_evaluation": {
            "status": "deferred",
            "reason": f"Test evaluation is deferred by the {spec.protocol} protocol.",
        },
        "seeds": [],
        "aggregate": _aggregate([]),
    }


def _load_summary(path: Path, spec: ExperimentSpec) -> dict[str, object]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise ValueError(f"invalid {spec.protocol} summary JSON: {path}") from error
    if not isinstance(payload, dict):
        raise ValueError(f"{spec.protocol} summary must be a JSON object: {path}")
    return cast(dict[str, object], payload)


def _environment_matches(
    recorded: object, current: dict[str, object], *, allow_drift: bool
) -> bool:
    """Whether a recorded environment may continue into ``current``.

    Strict by default: an exact match, as before. Under ``allow_drift`` only
    the keys that change results are compared, so a run may resume on another
    machine or another card -- each seed still records the environment it
    actually ran in, so what happened stays reconstructible.
    """
    if not allow_drift:
        return recorded == current
    if not isinstance(recorded, dict):
        return False
    return all(recorded.get(key) == current.get(key) for key in _NUMERIC_ENVIRONMENT_KEYS)


def _validate_summary_identity(
    summary: dict[str, object],
    manifest_sha256: str,
    configuration: dict[str, object],
    environment: dict[str, object],
    spec: ExperimentSpec,
    *,
    allow_environment_drift: bool = False,
) -> None:
    manifest = summary.get("feature_manifest")
    actual_manifest_hash = manifest.get("sha256") if isinstance(manifest, dict) else None
    if actual_manifest_hash != manifest_sha256:
        raise ValueError(
            f"existing {spec.protocol} summary uses a different feature manifest; "
            "choose a new output directory"
        )
    if summary.get("configuration") != configuration:
        raise ValueError(
            f"existing {spec.protocol} summary uses a different configuration; "
            "choose a new output directory"
        )
    if summary.get("configuration_sha256") != _canonical_hash(configuration):
        raise ValueError(
            f"existing {spec.protocol} summary has an invalid configuration fingerprint"
        )
    if not _environment_matches(
        summary.get("environment"), environment, allow_drift=allow_environment_drift
    ):
        raise ValueError(
            f"existing {spec.protocol} summary was created in a different runtime environment; "
            "choose a new output directory or pass --allow-environment-drift"
        )
    seeds = summary.get("seeds")
    if not isinstance(seeds, list):
        raise ValueError(f"existing {spec.protocol} summary has an invalid seeds list")
    recorded_seeds: list[int] = []
    for item in seeds:
        if not isinstance(item, dict) or item.get("seed") not in spec.seeds:
            raise ValueError(f"existing {spec.protocol} summary contains an invalid seed record")
        recorded_seeds.append(cast(int, item["seed"]))
    if len(recorded_seeds) != len(set(recorded_seeds)):
        raise ValueError(f"existing {spec.protocol} summary contains duplicate seed records")


def _seed_paths(output_dir: Path, seed: int) -> dict[str, Path]:
    seed_dir = output_dir / f"seed-{seed}"
    return {
        "checkpoint": seed_dir / "best.pt",
        "metrics": seed_dir / "metrics.json",
        "onnx_model": seed_dir / "onnx" / "engagement.onnx",
        "onnx_metadata": seed_dir / "onnx" / "engagement.metadata.json",
    }


def _artifact_records(output_dir: Path, paths: dict[str, Path]) -> dict[str, dict[str, object]]:
    records: dict[str, dict[str, object]] = {}
    for name, path in paths.items():
        if not path.is_file():
            raise FileNotFoundError(f"seed artifact not found: {path}")
        size_bytes = path.stat().st_size
        if size_bytes <= 0:
            raise RuntimeError(f"seed artifact is empty: {path}")
        records[name] = {
            "path": path.relative_to(output_dir).as_posix(),
            "sha256": _sha256(path),
            "size_bytes": size_bytes,
        }
    return records


def _assert_manifest_unchanged(
    path: Path, expected_sha256: str, boundary: str, spec: ExperimentSpec
) -> None:
    try:
        actual_sha256 = _sha256(path)
    except OSError as error:
        raise RuntimeError(f"feature manifest unavailable {boundary}: {path}") from error
    if actual_sha256 != expected_sha256:
        raise RuntimeError(
            f"feature manifest changed {boundary}; "
            f"refusing to complete an incompatible {spec.protocol} seed"
        )


def _seed_record(summary: dict[str, object], seed: int) -> dict[str, object] | None:
    seeds = cast(list[object], summary["seeds"])
    for item in seeds:
        if isinstance(item, dict) and item.get("seed") == seed:
            return cast(dict[str, object], item)
    return None


def _seed_is_complete(
    record: dict[str, object] | None,
    *,
    seed: int,
    output_dir: Path,
    manifest_sha256: str,
    configuration: dict[str, object],
    inputs: dict[str, object],
    spec: ExperimentSpec,
) -> tuple[bool, str]:
    if record is None:
        return False, "not recorded in summary"
    paths = _seed_paths(output_dir, seed)
    if record.get("status") != "complete":
        return False, "summary status is not complete"
    if record.get("feature_manifest_sha256") != manifest_sha256:
        return False, "feature manifest fingerprint does not match"
    if record.get("configuration_sha256") != _canonical_hash(configuration):
        return False, "configuration fingerprint does not match"
    for needed, key in (
        (spec.needs_reliability_manifest, "label_reliability"),
        (spec.needs_stage1_checkpoint, "stage1"),
    ):
        if not needed:
            continue
        recorded_inputs = record.get("inputs")
        recorded_input = recorded_inputs.get(key) if isinstance(recorded_inputs, dict) else None
        current_input = inputs.get(key)
        if (
            not isinstance(recorded_input, dict)
            or not isinstance(current_input, dict)
            or recorded_input.get("sha256") != current_input.get("sha256")
        ):
            return False, f"{key} input fingerprint does not match"
    artifacts = record.get("artifacts")
    if not isinstance(artifacts, dict):
        return False, "artifact integrity records are missing"
    for name, path in paths.items():
        artifact = artifacts.get(name)
        if not isinstance(artifact, dict):
            return False, f"{name} integrity record is missing"
        if artifact.get("path") != path.relative_to(output_dir).as_posix():
            return False, f"{name} path does not match"
        expected_hash = artifact.get("sha256")
        expected_size = artifact.get("size_bytes")
        if (
            not isinstance(expected_hash, str)
            or len(expected_hash) != 64
            or not isinstance(expected_size, int)
            or isinstance(expected_size, bool)
            or expected_size <= 0
        ):
            return False, f"{name} integrity record is invalid"
        if not path.is_file():
            return False, f"{name} is missing"
        if path.stat().st_size != expected_size or _sha256(path) != expected_hash:
            return False, f"{name} integrity check failed"
    try:
        metrics = json.loads(paths["metrics"].read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError, OSError):
        return False, "metrics.json is unreadable"
    if not isinstance(metrics, dict) or "test" in metrics:
        return False, "metrics.json is not validation-only"
    test_evaluation = metrics.get("test_evaluation")
    if not isinstance(test_evaluation, dict) or test_evaluation.get("status") != "deferred":
        return False, "metrics.json does not defer Test evaluation"
    experiment = metrics.get("experiment")
    if not isinstance(experiment, dict):
        return False, f"metrics.json has no {spec.protocol} identity"
    if (
        experiment.get("protocol") != spec.protocol
        or experiment.get("seed") != seed
        or experiment.get("feature_manifest_sha256") != manifest_sha256
        or experiment.get("configuration") != configuration
        or experiment.get("configuration_sha256") != _canonical_hash(configuration)
    ):
        return False, f"metrics.json {spec.protocol} identity does not match"
    validation = metrics.get("validation")
    recorded_validation = record.get("validation")
    if not isinstance(validation, dict) or not isinstance(recorded_validation, dict):
        return False, "validation metrics are missing"
    if (
        validation.get("accuracy") != recorded_validation.get("accuracy")
        or validation.get("macro_f1") != recorded_validation.get("macro_f1")
        or metrics.get("best_epoch") != record.get("best_epoch")
    ):
        return False, "recorded validation result does not match metrics.json"
    return True, "complete"


def _aggregate(seed_records: list[dict[str, object]]) -> dict[str, object]:
    accuracies: list[float] = []
    macro_f1s: list[float] = []
    within_ones: list[float] = []
    kappas: list[float] = []
    for record in seed_records:
        validation = record.get("validation")
        if isinstance(validation, dict):
            accuracies.append(float(validation["accuracy"]))
            macro_f1s.append(float(validation["macro_f1"]))
            # The ordinal metrics arrived later. Seed records completed before
            # them come back unchanged through the resume path, so a missing
            # entry has to be tolerated instead of failing the aggregate.
            if "within_one_accuracy" in validation:
                within_ones.append(float(validation["within_one_accuracy"]))
            if "quadratic_weighted_kappa" in validation:
                kappas.append(float(validation["quadratic_weighted_kappa"]))

    def summarize(values: list[float]) -> dict[str, float | None]:
        return {
            "mean": statistics.fmean(values) if values else None,
            "sample_standard_deviation": statistics.stdev(values) if len(values) >= 2 else None,
        }

    aggregate: dict[str, object] = {
        "completed_seed_count": len(seed_records),
        "validation_accuracy": summarize(accuracies),
        "validation_macro_f1": summarize(macro_f1s),
    }
    if within_ones:
        aggregate["validation_within_one_accuracy"] = summarize(within_ones)
    if kappas:
        aggregate["validation_quadratic_weighted_kappa"] = summarize(kappas)
    return aggregate


def _update_summary(summary: dict[str, object], spec: ExperimentSpec) -> None:
    records = [
        cast(dict[str, object], item)
        for item in cast(list[object], summary["seeds"])
        if isinstance(item, dict)
    ]
    records.sort(key=lambda item: int(item["seed"]))
    summary["seeds"] = records
    summary["aggregate"] = _aggregate(records)
    summary["status"] = (
        "complete" if [item["seed"] for item in records] == list(spec.seeds) else "in_progress"
    )


@dataclass(frozen=True, slots=True)
class RunContext:
    """Per-run facts every seed of a protocol must agree on.

    Built once by :func:`prepare_run` and handed to :func:`run_seed`. Parallel
    seed processes each build their own and must arrive at the same values --
    that agreement is what makes their records comparable.
    """

    spec: ExperimentSpec
    features_root: Path
    output_dir: Path
    device: str
    manifest_path: Path
    manifest_sha256: str
    configuration: dict[str, object]
    environment: dict[str, object]
    inputs: dict[str, object]


def _seed_record_path(output_dir: Path, seed: int) -> Path:
    return output_dir / f"seed-{seed}" / "record.json"


def _read_seed_record(output_dir: Path, seed: int) -> dict[str, object] | None:
    path = _seed_record_path(output_dir, seed)
    if not path.is_file():
        return None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError, OSError):
        return None
    return payload if isinstance(payload, dict) else None


def _adopt_summary_seed_records(context: RunContext, summary: dict[str, object]) -> None:
    """Give an older run the per-seed record files it never wrote.

    Seed completion used to live only in ``summary.json``. Concurrent seeds
    cannot share one file, so the record moved next to its checkpoint. Without
    this, every run finished under the old layout would look incomplete and
    retrain from scratch.
    """
    for item in cast(list[object], summary.get("seeds") or []):
        if not isinstance(item, dict):
            continue
        seed = item.get("seed")
        if not isinstance(seed, int) or isinstance(seed, bool):
            continue
        if seed not in context.spec.seeds:
            continue
        path = _seed_record_path(context.output_dir, seed)
        if not path.is_file():
            _write_json_atomic(path, dict(item))


def prepare_run(
    spec: ExperimentSpec,
    features_root: Path,
    output_dir: Path,
    *,
    device: str,
    graph_path: Path | None = None,
    reliability_path: Path | None = None,
    stage1_path: Path | None = None,
    allow_environment_drift: bool = False,
) -> RunContext:
    """Validate the inputs and pin the identity every seed of this run shares.

    Leaves ``summary.json`` alone apart from back-filling per-seed records for
    a run made before those existed, so concurrent seed processes may all call
    it.
    """
    try:
        parse_device(device)
    except ValueError as error:
        raise ValueError(f"{spec.protocol} {error}") from error
    manifest_path, manifest_sha256 = _validate_manifest(features_root, spec)
    if spec.needs_landmark_graph:
        resolved_graph = resolve_landmark_graph(features_root, graph_path or spec.graph_path)
        spec = replace(
            spec, graph_path=resolved_graph, build_model=stgcn_model_builder(resolved_graph)
        )
    if spec.needs_reliability_manifest:
        selected_reliability = reliability_path or spec.reliability_manifest
        if selected_reliability is None:
            raise ValueError(f"{spec.protocol} requires a reliability manifest")
        spec = replace(spec, reliability_manifest=selected_reliability.resolve())
    if spec.needs_stage1_checkpoint:
        selected_stage1 = stage1_path or spec.stage1_output
        if selected_stage1 is None:
            raise ValueError(f"{spec.protocol} requires a stage-1 output directory")
        spec = replace(spec, stage1_output=selected_stage1.resolve())
    inputs: dict[str, object] = {}
    graph_record = _graph_record(spec.graph_path)
    if graph_record is not None:
        inputs["landmark_graph"] = graph_record
    reliability_record = _reliability_record(
        spec.reliability_manifest,
        feature_manifest_sha256=manifest_sha256,
    )
    if reliability_record is not None:
        inputs["label_reliability"] = reliability_record
    stage1_record = _stage1_record(spec, spec.stage1_output)
    if stage1_record is not None:
        inputs["stage1"] = stage1_record
    configuration = _build_configuration(spec, device)
    environment = _environment(device, spec)
    _enable_strict_determinism(device)

    output_dir.mkdir(parents=True, exist_ok=True)
    context = RunContext(
        spec=spec,
        features_root=features_root,
        output_dir=output_dir,
        device=device,
        manifest_path=manifest_path,
        manifest_sha256=manifest_sha256,
        configuration=configuration,
        environment=environment,
        inputs=inputs,
    )
    summary_path = output_dir / "summary.json"
    if summary_path.is_file():
        summary = _load_summary(summary_path, spec)
        _validate_summary_identity(
            summary,
            manifest_sha256,
            configuration,
            environment,
            spec,
            allow_environment_drift=allow_environment_drift,
        )
        _validate_inputs_identity(summary, inputs, spec)
        _adopt_summary_seed_records(context, summary)
    return context


def _train_one_seed(context: RunContext, seed: int, seed_dir: Path) -> dict[str, object]:
    """Train, export and fingerprint one seed; return its completion record."""
    spec = context.spec
    manifest_path = context.manifest_path
    manifest_sha256 = context.manifest_sha256
    configuration = context.configuration
    reliability_input = context.inputs.get("label_reliability")
    stage1_input = context.inputs.get("stage1")

    def assert_external_inputs_unchanged(boundary: str) -> None:
        if isinstance(reliability_input, dict):
            _assert_file_record_unchanged(
                cast(dict[str, object], reliability_input),
                "label_reliability",
                boundary,
            )
        if isinstance(stage1_input, dict):
            # Only this seed's checkpoint: the others are not read by this run.
            seeds = cast(dict[str, object], stage1_input["seeds"])
            _assert_file_record_unchanged(
                cast(dict[str, object], seeds[str(seed)]),
                f"stage-1 checkpoint for seed {seed}",
                boundary,
            )

    def report_progress(epoch: int, metrics: EvaluationMetrics) -> None:
        total_epochs = spec.max_epochs + (
            spec.reliable_warmup_epochs if spec.curriculum != "none" else 0
        )
        print(
            f"{spec.protocol} seed={seed} epoch={epoch + 1}/{total_epochs} "
            f"validation_accuracy={metrics.accuracy:.6f} "
            f"validation_macro_f1={metrics.macro_f1:.6f}",
            flush=True,
        )

    training_config = TrainingConfig(
        features_root=context.features_root,
        output_dir=seed_dir,
        max_epochs=spec.max_epochs,
        batch_size=spec.batch_size,
        learning_rate=spec.learning_rate,
        patience=spec.patience,
        seed=seed,
        device=context.device,
        class_weighting=spec.class_weighting,
        loss=spec.loss,
        focal_gamma=spec.focal_gamma,
        sampler=spec.sampler,
        target_encoding=spec.target_encoding,
        sord_alpha=spec.sord_alpha,
        num_workers=0,
        deterministic=True,
        model=spec.model_config,
        build_model=spec.build_model,
        needs_feature_stats=spec.needs_feature_stats,
        lr_step=spec.lr_step,
        array_key=spec.array_key,
        array_shape=spec.array_shape,
        stage1_checkpoint=(
            spec.stage1_output / f"seed-{seed}" / "best.pt"
            if spec.needs_stage1_checkpoint and spec.stage1_output is not None
            else None
        ),
        curriculum=spec.curriculum,
        reliability_manifest=spec.reliability_manifest,
        reliable_warmup_epochs=spec.reliable_warmup_epochs,
        ambiguous_target_encoding=spec.ambiguous_target_encoding,
        ambiguous_neighbor_mass=spec.ambiguous_neighbor_mass,
    )
    _assert_manifest_unchanged(manifest_path, manifest_sha256, f"before seed {seed} training", spec)
    assert_external_inputs_unchanged(f"before seed {seed} training")
    result = train_model(training_config, evaluate_test=False, progress=report_progress)
    _assert_manifest_unchanged(manifest_path, manifest_sha256, f"after seed {seed} training", spec)
    assert_external_inputs_unchanged(f"after seed {seed} training")
    metrics_payload = _load_summary(result.metrics_path, spec)
    metrics_payload["experiment"] = {
        "protocol": spec.protocol,
        "seed": seed,
        "feature_manifest_sha256": manifest_sha256,
        "configuration": configuration,
        "configuration_sha256": _canonical_hash(configuration),
        "inputs": context.inputs,
    }
    metrics_payload["test_evaluation"] = {
        "status": "deferred",
        "reason": f"Test evaluation is deferred by the {spec.protocol} protocol.",
    }
    metrics_payload.pop("test", None)
    _write_json_atomic(result.metrics_path, metrics_payload)

    model = load_checkpoint(result.checkpoint_path)
    _assert_manifest_unchanged(
        manifest_path, manifest_sha256, f"before seed {seed} ONNX export", spec
    )
    export_metadata = (
        DeploymentMetadata.for_stgcn()
        if spec.schema is None
        else DeploymentMetadata.for_schema(spec.schema)
    )
    exported = export_onnx(model, export_metadata, seed_dir / "onnx")
    _assert_manifest_unchanged(
        manifest_path, manifest_sha256, f"after seed {seed} ONNX export", spec
    )
    expected_paths = _seed_paths(context.output_dir, seed)
    if exported.model_path != expected_paths["onnx_model"]:
        raise RuntimeError("ONNX exporter returned an unexpected model path")
    if exported.metadata_path != expected_paths["onnx_metadata"]:
        raise RuntimeError("ONNX exporter returned an unexpected metadata path")
    artifacts = _artifact_records(context.output_dir, expected_paths)
    _assert_manifest_unchanged(
        manifest_path, manifest_sha256, f"before recording seed {seed} completion", spec
    )
    assert_external_inputs_unchanged(f"before recording seed {seed} completion")
    return {
        "seed": seed,
        "status": "complete",
        "feature_manifest_sha256": manifest_sha256,
        "configuration_sha256": _canonical_hash(configuration),
        "inputs": context.inputs,
        # Per-seed snapshot: with drift allowed, the summary's top-level
        # environment describes only the most recent run, so this is the
        # only record of which machine and card produced this checkpoint.
        "environment": context.environment,
        "best_epoch": result.best_epoch,
        "validation": {
            "accuracy": result.validation.accuracy,
            "macro_f1": result.validation.macro_f1,
            "within_one_accuracy": result.validation.within_one_accuracy,
            "quadratic_weighted_kappa": result.validation.quadratic_weighted_kappa,
        },
        "artifacts": artifacts,
    }


def run_seed(context: RunContext, seed: int) -> bool:
    """Train one seed unless it is already complete; report whether it ran.

    Writes only inside ``seed-<n>/`` and never touches ``summary.json``, so
    several seeds can run as concurrent processes against one output
    directory. Call :func:`collect_summary` once they finish.
    """
    spec = context.spec
    if seed not in spec.seeds:
        raise ValueError(f"{spec.protocol} has no seed {seed}; expected one of {list(spec.seeds)}")
    seed_dir = context.output_dir / f"seed-{seed}"
    lock = DirectoryLock(
        seed_dir / ".seed.lock",
        busy_message=(
            f"{spec.protocol} seed {seed} is already running in {seed_dir}; "
            "wait for that process or choose another --output"
        ),
    )
    with lock:
        existing = _read_seed_record(context.output_dir, seed)
        complete, reason = _seed_is_complete(
            existing,
            seed=seed,
            output_dir=context.output_dir,
            manifest_sha256=context.manifest_sha256,
            configuration=context.configuration,
            inputs=context.inputs,
            spec=spec,
        )
        if complete:
            print(f"{spec.protocol} seed={seed} resume=complete", flush=True)
            return False
        if existing is not None:
            print(f"{spec.protocol} seed={seed} resume=rerun reason={reason}", flush=True)
            _seed_record_path(context.output_dir, seed).unlink(missing_ok=True)
        record = _train_one_seed(context, seed, seed_dir)
        _write_json_atomic(_seed_record_path(context.output_dir, seed), record)
        validation = cast(dict[str, float], record["validation"])
        print(
            f"{spec.protocol} seed={seed} complete best_epoch={record['best_epoch']} "
            f"validation_macro_f1={validation['macro_f1']:.6f}",
            flush=True,
        )
        return True


def collect_summary(
    context: RunContext, *, allow_environment_drift: bool = False
) -> E0ExperimentResult:
    """Rebuild ``summary.json`` from the per-seed records currently on disk.

    Safe to call at any point -- seeds still running are simply absent from it.
    """
    spec = context.spec
    summary_path = context.output_dir / "summary.json"
    if summary_path.is_file():
        summary = _load_summary(summary_path, spec)
        _validate_summary_identity(
            summary,
            context.manifest_sha256,
            context.configuration,
            context.environment,
            spec,
            allow_environment_drift=allow_environment_drift,
        )
        _validate_inputs_identity(summary, context.inputs, spec)
        if allow_environment_drift:
            # Record that the strict check was waived, and keep the latest
            # environment so the summary describes the run that is continuing.
            summary["environment_drift_allowed"] = True
            summary["environment"] = context.environment
    else:
        summary = _empty_summary(
            context.manifest_path,
            context.manifest_sha256,
            context.configuration,
            context.environment,
            spec,
            context.inputs,
        )
    records: list[object] = []
    for seed in spec.seeds:
        record = _read_seed_record(context.output_dir, seed)
        complete, _ = _seed_is_complete(
            record,
            seed=seed,
            output_dir=context.output_dir,
            manifest_sha256=context.manifest_sha256,
            configuration=context.configuration,
            inputs=context.inputs,
            spec=spec,
        )
        if complete and record is not None:
            records.append(record)
    summary["seeds"] = records
    _update_summary(summary, spec)
    _write_json_atomic(summary_path, summary)
    completed = tuple(
        int(cast(int, cast(dict[str, object], item)["seed"]))
        for item in cast(list[object], summary["seeds"])
    )
    return E0ExperimentResult(summary_path, completed)


def reproduce_experiment(
    spec: ExperimentSpec,
    features_root: Path,
    output_dir: Path,
    *,
    device: str,
    graph_path: Path | None = None,
    reliability_path: Path | None = None,
    stage1_path: Path | None = None,
    allow_environment_drift: bool = False,
    seeds: Sequence[int] | None = None,
    collect: bool = True,
) -> E0ExperimentResult:
    """Run a protocol's seeds in this process and summarize them.

    ``seeds`` restricts the run to a subset -- one seed per process is how
    parallel execution is driven. Those workers pass ``collect=False`` so they
    never write ``summary.json``; :func:`collect_only` rebuilds it afterwards.
    """
    context = prepare_run(
        spec,
        features_root,
        output_dir,
        device=device,
        graph_path=graph_path,
        reliability_path=reliability_path,
        stage1_path=stage1_path,
        allow_environment_drift=allow_environment_drift,
    )
    for seed in context.spec.seeds if seeds is None else seeds:
        run_seed(context, seed)
        if collect:
            collect_summary(context, allow_environment_drift=allow_environment_drift)
    if not collect:
        return E0ExperimentResult(
            output_dir / "summary.json",
            tuple(
                seed
                for seed in context.spec.seeds
                if _read_seed_record(output_dir, seed) is not None
            ),
        )
    return collect_summary(context, allow_environment_drift=allow_environment_drift)


def collect_only(
    spec: ExperimentSpec,
    features_root: Path,
    output_dir: Path,
    *,
    device: str,
    graph_path: Path | None = None,
    reliability_path: Path | None = None,
    stage1_path: Path | None = None,
    allow_environment_drift: bool = False,
) -> E0ExperimentResult:
    """Rebuild ``summary.json`` without training, after parallel seeds finish."""
    context = prepare_run(
        spec,
        features_root,
        output_dir,
        device=device,
        graph_path=graph_path,
        reliability_path=reliability_path,
        stage1_path=stage1_path,
        allow_environment_drift=allow_environment_drift,
    )
    return collect_summary(context, allow_environment_drift=allow_environment_drift)


def reproduce_e0(
    features_root: Path,
    output_dir: Path,
    *,
    device: str,
    allow_environment_drift: bool = False,
) -> E0ExperimentResult:
    return reproduce_experiment(
        E0_SPEC,
        features_root,
        output_dir,
        device=device,
        allow_environment_drift=allow_environment_drift,
    )


__all__ = [
    "E0A_SPEC",
    "E0B_SPEC",
    "E0C_SPEC",
    "E0D_SPEC",
    "E0I_SPEC",
    "E0J_SPEC",
    "E0K_SPEC",
    "E0L_SPEC",
    "E0_SEEDS",
    "E0_SPEC",
    "E1A_SPEC",
    "E1B_SPEC",
    "E1_SPEC",
    "SPECS",
    "E0ExperimentResult",
    "ExperimentSpec",
    "collect_only",
    "collect_summary",
    "prepare_run",
    "reproduce_e0",
    "reproduce_experiment",
    "run_seed",
    "stgcn_model_builder",
]
