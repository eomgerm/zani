"""The publisher must move metrics and nothing else.

A checkpoint slipping into the results branch would make it unusable, and a
half-written ``metrics.json`` would commit malformed JSON: ``training.py``
writes that file with a plain ``write_text``, without the atomic rename the
other metric files get.
"""

from __future__ import annotations

import json
from pathlib import Path

from zani_ai.engagement.publish import collect_metrics


def _write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(text.encode("utf-8"))


def _artifacts(root: Path) -> Path:
    """A protocol output directory shaped like a real ``--output`` tree."""
    artifacts = root / "artifacts" / "engagement"
    _write(artifacts / "e1" / "summary.json", '{"protocol": "E1"}\n')
    _write(artifacts / "e1" / "test_results.json", '{"accuracy": 0.5}\n')
    _write(artifacts / "e1" / "seed-42" / "record.json", '{"seed": 42}\n')
    _write(artifacts / "e1" / "seed-42" / "metrics.json", '{"macro_f1": 0.4}\n')
    _write(artifacts / "e1" / "engagenet_e1_reproduction_report.html", "<html></html>")
    _write(artifacts / "e1" / "seed-42" / "best.pt", "not really a checkpoint")
    _write(artifacts / "e1" / "seed-42" / ".seed.lock", "\0")
    _write(artifacts / "e1" / "seed-42" / "scratch.json", '{"unrelated": true}\n')
    return artifacts


def test_collects_only_the_four_metric_files(tmp_path: Path) -> None:
    artifacts = _artifacts(tmp_path)

    relatives = {str(metric.relative).replace("\\", "/") for metric in collect_metrics(artifacts)}

    assert relatives == {
        "e1/summary.json",
        "e1/test_results.json",
        "e1/seed-42/record.json",
        "e1/seed-42/metrics.json",
    }


def test_skips_unparseable_metrics_and_keeps_the_rest(tmp_path: Path) -> None:
    artifacts = _artifacts(tmp_path)
    _write(artifacts / "e1" / "seed-42" / "metrics.json", '{"macro_f1": 0.4')

    relatives = {str(metric.relative).replace("\\", "/") for metric in collect_metrics(artifacts)}

    assert "e1/seed-42/metrics.json" not in relatives
    assert "e1/summary.json" in relatives


def test_carries_the_bytes_that_parsed(tmp_path: Path) -> None:
    artifacts = _artifacts(tmp_path)

    by_name = {metric.relative.name: metric for metric in collect_metrics(artifacts)}

    assert by_name["summary.json"].data == b'{"protocol": "E1"}\n'
    assert json.loads(by_name["summary.json"].data) == {"protocol": "E1"}
