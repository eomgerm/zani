from __future__ import annotations

from pathlib import Path

import pytest

from zani_ai.engagement.runtime import (
    GRAPH_FILENAME,
    GRAPH_PATH_ENV,
    device_type,
    parse_device,
    resolve_landmark_graph,
)


@pytest.mark.parametrize(
    ("device", "expected"),
    [
        ("cpu", ("cpu", None)),
        ("cuda", ("cuda", None)),
        ("cuda:0", ("cuda", 0)),
        ("cuda:2", ("cuda", 2)),
        ("  cuda:2  ", ("cuda", 2)),
    ],
)
def test_parse_device_accepts_supported_forms(
    device: str, expected: tuple[str, int | None]
) -> None:
    assert parse_device(device) == expected


@pytest.mark.parametrize("device", ["", "gpu", "cpu:0", "cuda:", "cuda:-1", "cuda:a", "CUDA"])
def test_parse_device_rejects_everything_else(device: str) -> None:
    with pytest.raises(ValueError, match="device must be"):
        parse_device(device)


def test_device_type_strips_the_card_index() -> None:
    assert device_type("cuda:2") == device_type("cuda") == "cuda"


def _write_graph(path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"not-a-real-npz")
    return path


def test_graph_resolves_beside_the_features_root(tmp_path: Path) -> None:
    features = tmp_path / "processed" / "e1"
    features.mkdir(parents=True)
    expected = _write_graph(features / GRAPH_FILENAME)

    assert resolve_landmark_graph(features) == expected


def test_graph_resolves_one_level_above_the_features_root(tmp_path: Path) -> None:
    """build-features leaves the graph in the shared processed/<dataset>/ dir."""
    features = tmp_path / "processed" / "e1"
    features.mkdir(parents=True)
    expected = _write_graph(features.parent / GRAPH_FILENAME)

    assert resolve_landmark_graph(features) == expected


def test_graph_beside_the_features_root_wins(tmp_path: Path) -> None:
    features = tmp_path / "processed" / "e1"
    features.mkdir(parents=True)
    expected = _write_graph(features / GRAPH_FILENAME)
    _write_graph(features.parent / GRAPH_FILENAME)

    assert resolve_landmark_graph(features) == expected


def test_explicit_graph_overrides_the_search(tmp_path: Path) -> None:
    features = tmp_path / "processed" / "e1"
    features.mkdir(parents=True)
    _write_graph(features / GRAPH_FILENAME)
    expected = _write_graph(tmp_path / "elsewhere" / "other.npz")

    assert resolve_landmark_graph(features, expected) == expected


def test_environment_variable_overrides_the_search(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    features = tmp_path / "processed" / "e1"
    features.mkdir(parents=True)
    _write_graph(features / GRAPH_FILENAME)
    expected = _write_graph(tmp_path / "elsewhere" / "other.npz")
    monkeypatch.setenv(GRAPH_PATH_ENV, str(expected))

    assert resolve_landmark_graph(features) == expected


def test_explicit_graph_outranks_the_environment_variable(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    features = tmp_path / "processed" / "e1"
    features.mkdir(parents=True)
    monkeypatch.setenv(GRAPH_PATH_ENV, str(_write_graph(tmp_path / "from-env.npz")))
    expected = _write_graph(tmp_path / "explicit.npz")

    assert resolve_landmark_graph(features, expected) == expected


def test_a_stated_graph_path_that_is_missing_fails_loudly(tmp_path: Path) -> None:
    """Falling back after someone stated an intent would hide stale config."""
    features = tmp_path / "processed" / "e1"
    features.mkdir(parents=True)
    _write_graph(features / GRAPH_FILENAME)

    with pytest.raises(FileNotFoundError, match="--graph"):
        resolve_landmark_graph(features, tmp_path / "missing.npz")


def test_a_stated_environment_variable_that_is_missing_fails_loudly(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    features = tmp_path / "processed" / "e1"
    features.mkdir(parents=True)
    _write_graph(features / GRAPH_FILENAME)
    monkeypatch.setenv(GRAPH_PATH_ENV, str(tmp_path / "missing.npz"))

    with pytest.raises(FileNotFoundError, match=GRAPH_PATH_ENV):
        resolve_landmark_graph(features)


def test_missing_graph_lists_every_path_tried(tmp_path: Path) -> None:
    features = tmp_path / "processed" / "e1"
    features.mkdir(parents=True)

    with pytest.raises(FileNotFoundError) as error:
        resolve_landmark_graph(features)

    message = str(error.value)
    assert str(features / GRAPH_FILENAME) in message
    assert str(features.parent / GRAPH_FILENAME) in message
