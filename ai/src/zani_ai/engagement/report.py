from __future__ import annotations

import hashlib
import html
import importlib.metadata
import json
import os
import platform
import statistics
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, cast

import numpy as np
import torch

from zani_ai.engagement.contracts import LABELS
from zani_ai.engagement.experiment import E0_SPEC, E0A_SPEC, ExperimentSpec
from zani_ai.engagement.runtime import parse_device
from zani_ai.engagement.training import (
    TrainingConfig,
    _load_feature_datasets,
    _loader,
    evaluate_model,
    load_checkpoint,
    validate_manifest_completion,
)

PAPER_VALIDATION_ACCURACY = 0.6910
PAPER_TEST_ACCURACY = 0.6761
REPORT_FILENAME = "engagenet_e0_reproduction_report.html"
RESULTS_FILENAME = "test_results.json"


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _load_json(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid JSON: {path}") from error
    if not isinstance(payload, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return cast(dict[str, Any], payload)


def _write_text_atomic(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    try:
        temporary.write_text(text, encoding="utf-8")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def _write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    _write_text_atomic(path, json.dumps(payload, ensure_ascii=False, indent=2) + "\n")


def _summary_seed(summary: dict[str, Any], seed: int) -> dict[str, Any]:
    for item in summary.get("seeds", []):
        if isinstance(item, dict) and item.get("seed") == seed:
            return cast(dict[str, Any], item)
    raise ValueError(f"E0 summary is missing seed {seed}")


def _checkpoint(output_dir: Path, record: dict[str, Any]) -> tuple[Path, str]:
    artifact = record.get("artifacts", {}).get("checkpoint")
    if not isinstance(artifact, dict):
        raise ValueError("checkpoint integrity record is missing")
    relative = artifact.get("path")
    expected_hash = artifact.get("sha256")
    expected_size = artifact.get("size_bytes")
    if not isinstance(relative, str) or not isinstance(expected_hash, str):
        raise ValueError("checkpoint integrity record is invalid")
    path = output_dir / relative
    if not path.is_file() or path.stat().st_size != expected_size:
        raise ValueError(f"checkpoint is missing or changed: {path}")
    if _sha256(path) != expected_hash:
        raise ValueError(f"checkpoint hash mismatch: {path}")
    return path, expected_hash


def _summarize(values: list[float]) -> dict[str, float]:
    return {
        "mean": statistics.fmean(values),
        "sample_standard_deviation": statistics.stdev(values),
    }


def _aggregate(records: list[dict[str, Any]]) -> dict[str, Any]:
    accuracies = [float(item["test"]["accuracy"]) for item in records]
    macro_f1s = [float(item["test"]["macro_f1"]) for item in records]
    pooled = np.sum(
        [np.asarray(item["test"]["confusion_matrix"], dtype=np.int64) for item in records],
        axis=0,
    )
    per_class: dict[str, Any] = {}
    for label in LABELS:
        reports = [cast(dict[str, Any], item["test"]["classification_report"])[label] for item in records]
        per_class[label] = {
            metric: _summarize([float(report[metric]) for report in reports])
            for metric in ("precision", "recall", "f1-score")
        }
        per_class[label]["support_per_seed"] = int(reports[0]["support"])
    return {
        "completed_seed_count": len(records),
        "test_accuracy": _summarize(accuracies),
        "test_macro_f1": _summarize(macro_f1s),
        "pooled_confusion_matrix": pooled.tolist(),
        "per_class": per_class,
    }


def evaluate_frozen_checkpoints(
    features_root: Path, output_dir: Path, *, device: str, spec: ExperimentSpec
) -> Path:
    """Evaluate each validation-selected checkpoint on Test exactly once.

    Results are committed atomically after every seed. A valid recorded seed is never
    evaluated again, which keeps Test strictly post-selection even after interruption.
    """

    protocol = f"{spec.protocol}-fixed-checkpoint-test"
    try:
        kind, _ = parse_device(device)
    except ValueError as error:
        raise ValueError(f"{spec.protocol} evaluation {error}") from error
    if kind == "cuda" and not torch.cuda.is_available():
        raise RuntimeError(f"{spec.protocol} Test evaluation requested CUDA, but CUDA is unavailable")
    summary_path = output_dir / "summary.json"
    manifest_path = features_root / "manifest.json"
    summary = _load_json(summary_path)
    manifest = _load_json(manifest_path)
    validate_manifest_completion(manifest)
    if summary.get("status") != "complete":
        raise ValueError(f"five-seed {spec.protocol} training must be complete before Test evaluation")
    manifest_hash = _sha256(manifest_path)
    summary_manifest = summary.get("feature_manifest", {})
    if summary_manifest.get("sha256") != manifest_hash:
        raise ValueError(f"{spec.protocol} summary and feature manifest fingerprints differ")

    results_path = output_dir / RESULTS_FILENAME
    if results_path.is_file():
        results = _load_json(results_path)
        if (
            results.get("protocol") != protocol
            or results.get("feature_manifest_sha256") != manifest_hash
            or results.get("configuration_sha256") != summary.get("configuration_sha256")
        ):
            raise ValueError(f"existing Test results belong to a different {spec.protocol} run")
    else:
        results = {
            "protocol": protocol,
            "status": "in_progress",
            "selection_policy": "Validation-only; Test is evaluated once after all checkpoints are frozen.",
            "feature_manifest_sha256": manifest_hash,
            "configuration_sha256": summary.get("configuration_sha256"),
            "seeds": [],
        }
        _write_json_atomic(results_path, results)

    recorded = {
        int(item["seed"]): item
        for item in results.get("seeds", [])
        if isinstance(item, dict) and item.get("seed") in spec.seeds
    }
    datasets = None
    for seed in spec.seeds:
        summary_record = _summary_seed(summary, seed)
        checkpoint_path, checkpoint_hash = _checkpoint(output_dir, summary_record)
        existing = recorded.get(seed)
        if existing is not None:
            if existing.get("checkpoint_sha256") != checkpoint_hash:
                raise ValueError(f"recorded Test result checkpoint mismatch for seed {seed}")
            print(f"{spec.protocol} Test seed={seed} resume=complete", flush=True)
            continue
        if datasets is None:
            datasets = _load_feature_datasets(
                features_root,
                include_test=True,
                expected_schema=spec.schema_name,
                array_key=spec.array_key,
                array_shape=spec.array_shape,
            )
        if datasets.test is None:
            raise RuntimeError("Test feature split is unavailable")
        config = TrainingConfig(
            features_root=features_root,
            output_dir=output_dir / f"seed-{seed}",
            batch_size=32,
            seed=seed,
            device=device,
            num_workers=0,
            deterministic=True,
            array_key=spec.array_key,
            array_shape=spec.array_shape,
        )
        model = load_checkpoint(checkpoint_path, device)
        metrics = evaluate_model(
            model,
            _loader(datasets.test, config, shuffle=False),
            torch.device(device),
        )
        record = {
            "seed": seed,
            "checkpoint_path": checkpoint_path.relative_to(output_dir).as_posix(),
            "checkpoint_sha256": checkpoint_hash,
            "validation": summary_record["validation"],
            "test": metrics.to_dict(),
        }
        cast(list[dict[str, Any]], results["seeds"]).append(record)
        results["seeds"] = sorted(results["seeds"], key=lambda item: item["seed"])
        _write_json_atomic(results_path, results)
        print(
            f"{spec.protocol} Test seed={seed} complete accuracy={metrics.accuracy:.6f} "
            f"macro_f1={metrics.macro_f1:.6f}",
            flush=True,
        )

    records = cast(list[dict[str, Any]], results["seeds"])
    if [item["seed"] for item in records] != list(spec.seeds):
        raise RuntimeError(f"not all {spec.protocol} seeds have Test results")
    results["aggregate"] = _aggregate(records)
    results["status"] = "complete"
    results["completed_at_utc"] = datetime.now(UTC).isoformat()
    _write_json_atomic(results_path, results)
    summary["test_evaluation"] = {
        "status": "complete",
        "policy": "Validation-only selection, then one frozen-checkpoint Test pass per seed.",
        "results_path": results_path.relative_to(output_dir).as_posix(),
        "results_sha256": _sha256(results_path),
        "aggregate": results["aggregate"],
    }
    _write_json_atomic(summary_path, summary)
    return results_path


def evaluate_frozen_e0_checkpoints(
    features_root: Path, output_dir: Path, *, device: str
) -> Path:
    """Backward-compatible E0 wrapper; identical output to before generalization."""

    return evaluate_frozen_checkpoints(features_root, output_dir, device=device, spec=E0_SPEC)


def _pct(value: float) -> str:
    return f"{value * 100:.2f}%"


def _mean_std(value: dict[str, Any]) -> str:
    return f"{_pct(float(value['mean']))} ± {float(value['sample_standard_deviation']) * 100:.2f}%p"


def _table(headers: list[str], rows: list[list[object]], *, class_name: str = "") -> str:
    head = "".join(f"<th>{html.escape(str(cell))}</th>" for cell in headers)
    body = "".join(
        "<tr>" + "".join(f"<td>{html.escape(str(cell))}</td>" for cell in row) + "</tr>"
        for row in rows
    )
    return f'<div class="table-wrap"><table class="{class_name}"><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table></div>'


def _package_versions() -> dict[str, str]:
    packages = ("mediapipe", "opencv-python", "torch", "numpy", "scikit-learn", "onnx", "onnxruntime")
    versions: dict[str, str] = {}
    for package in packages:
        try:
            versions[package] = importlib.metadata.version(package)
        except importlib.metadata.PackageNotFoundError:
            versions[package] = "not installed"
    return versions


def _hardware(summary: dict[str, Any]) -> dict[str, str]:
    environment = cast(dict[str, Any], summary.get("environment", {}))
    cuda_device = environment.get("cuda_device") or {}
    values = {
        "OS": platform.platform(),
        "CPU": platform.processor() or "unknown",
        "논리 CPU": str(os.cpu_count() or "unknown"),
        "GPU": str(cuda_device.get("name", "unknown")),
        "CUDA Runtime": str(environment.get("cuda_runtime", "unknown")),
        "Python": str(environment.get("python", platform.python_version())),
    }
    try:
        import psutil

        values["RAM"] = f"{psutil.virtual_memory().total / 1024**3:.1f} GiB"
    except ImportError:
        values["RAM"] = "unknown"
    return values


def _counter_rows(counter: Counter[str]) -> list[list[object]]:
    return [[key, value] for key, value in sorted(counter.items(), key=lambda item: (-item[1], item[0]))]


def generate_html_report(
    features_root: Path,
    output_dir: Path,
    *,
    spec: ExperimentSpec,
    paper_validation: float = PAPER_VALIDATION_ACCURACY,
    paper_test: float = PAPER_TEST_ACCURACY,
    face_landmarker_model: Path | None = None,
    preparation_manifest: Path | None = None,
    threshold_manifest: Path | None = None,
) -> Path:
    schema = spec.schema
    if schema is not None:
        token_feature_count = schema.token_feature_count
        blendshape_count = len(schema.blendshape_names)
        title_family = "MediaPipe"
        model_title = "Transformer"
        eyebrow_label = f"MEDIAPIPE {token_feature_count}D"
    else:
        token_feature_count = None
        blendshape_count = None
        title_family = "ST-GCN"
        model_title = "ST-GCN"
        eyebrow_label = f"ST-GCN · {spec.schema_name}"
    report_filename = f"engagenet_{spec.protocol.lower().replace('-', '')}_reproduction_report.html"
    summary = _load_json(output_dir / "summary.json")
    results = _load_json(output_dir / RESULTS_FILENAME)
    manifest = _load_json(features_root / "manifest.json")
    if results.get("status") != "complete":
        raise ValueError("Test evaluation must be complete before report generation")
    included, excluded = validate_manifest_completion(manifest)
    preparation = _load_json(preparation_manifest) if preparation_manifest and preparation_manifest.is_file() else {}
    threshold = _load_json(threshold_manifest) if threshold_manifest and threshold_manifest.is_file() else {}
    aggregate = cast(dict[str, Any], results["aggregate"])
    validation = cast(dict[str, Any], summary["aggregate"])
    test_accuracy = cast(dict[str, Any], aggregate["test_accuracy"])
    test_macro_f1 = cast(dict[str, Any], aggregate["test_macro_f1"])

    seed_rows: list[list[object]] = []
    checkpoint_rows: list[list[object]] = []
    for item in cast(list[dict[str, Any]], results["seeds"]):
        seed = int(item["seed"])
        summary_seed = _summary_seed(summary, seed)
        seed_rows.append(
            [
                seed,
                int(summary_seed["best_epoch"]) + 1,
                _pct(float(item["validation"]["accuracy"])),
                _pct(float(item["validation"]["macro_f1"])),
                _pct(float(item["test"]["accuracy"])),
                _pct(float(item["test"]["macro_f1"])),
            ]
        )
        checkpoint_rows.append([seed, item["checkpoint_path"], item["checkpoint_sha256"]])

    pooled = cast(list[list[int]], aggregate["pooled_confusion_matrix"])
    confusion_rows = [[LABELS[index], *row] for index, row in enumerate(pooled)]
    class_rows: list[list[object]] = []
    for label in LABELS:
        metrics = aggregate["per_class"][label]
        class_rows.append(
            [
                label,
                _mean_std(metrics["precision"]),
                _mean_std(metrics["recall"]),
                _mean_std(metrics["f1-score"]),
                metrics["support_per_seed"],
            ]
        )

    included_split = Counter(str(item["split"]) for item in included)
    excluded_split = Counter(str(item["split"]) for item in excluded)
    excluded_reason = Counter(str(item["reason"]) for item in excluded)
    split_rows = [
        [split, included_split[split], excluded_split[split], included_split[split] + excluded_split[split]]
        for split in ("train", "valid", "test")
    ]
    subject_counts = preparation.get("subject_counts_by_split", {})
    provenance_rows = [
        ["공식 공개 설명", 90, 11, 26, 127],
        [
            "현재 제공 데이터",
            subject_counts.get("train", 91),
            subject_counts.get("valid", 11),
            subject_counts.get("test", 26),
            sum(int(value) for value in subject_counts.values()) if subject_counts else 128,
        ],
    ]
    original_excluded = len(threshold.get("excluded", [])) if threshold else 973
    original_total = int(threshold.get("total_count", 11206)) if threshold else 11206
    original_fraction = float(threshold.get("excluded_fraction", original_excluded / original_total)) if threshold else original_excluded / original_total
    versions = _package_versions()
    environment_rows = [[key, value] for key, value in _hardware(summary).items()]
    environment_rows.extend([[name, version] for name, version in versions.items()])
    if face_landmarker_model and face_landmarker_model.is_file():
        environment_rows.append(["Face Landmarker 모델 SHA-256", _sha256(face_landmarker_model)])
        environment_rows.append(["Face Landmarker 모델 크기", f"{face_landmarker_model.stat().st_size:,} bytes"])

    if schema is not None:
        adaptation_section = (
            f"<h2>MediaPipe {token_feature_count}D 적응점</h2>"
            f"<section class=\"panel\">{_table(['단계', '구성', '차원'], [['프레임 Gaze', '양안 iris 상대 위치·평균·차이', str(schema.gaze_dim)], ['프레임 Head Pose', 'yaw/pitch/roll, nose x/y, inverse interocular', str(schema.head_dim)], ['프레임 얼굴 동작', f'MediaPipe blendshape {blendshape_count}종', str(blendshape_count)], ['프레임 합계', f'{schema.gaze_dim} + {schema.head_dim} + {blendshape_count}', str(schema.raw_feature_count)], ['시간 집계', '10 FPS, 10초, 20구간별 평균+모표준편차', f'20 × {token_feature_count}']])}"
            f"<p>Transformer는 {token_feature_count}→256 투영, learned position 20개, encoder 4층·8 heads, max pooling, 256→128→4 분류기를 사용했다. 원 논문의 OpenFace 특징과 의미·스케일이 동일하지 않은 적응형 재현이다.</p></section>"
        )
    else:
        adaptation_section = (
            "<h2>ST-GCN 입력 표현</h2>"
            f"<section class=\"panel\">{_table(['단계', '구성', '차원'], [['채널', 'xyz 정규화 좌표', '3'], ['시간 스텝', '10 FPS, 10초 시퀀스', '100'], ['랜드마크 노드', f'MediaPipe landmark 그래프 ({spec.schema_name})', '78'], ['입력 shape', '[채널, 시간, 노드]', '[3, 100, 78]']])}"
            "<p>모델은 ST-GCN(spatial-configuration, 3블록 64→128→256 채널)을 사용해 시공간 그래프 컨볼루션으로 분류했다. "
            "원 논문의 OpenFace Gaze·Head Pose·AU 특징과는 표현 형식이 근본적으로 다른 적응형 재현이며, 토큰 기반 차원 수치는 없다.</p></section>"
        )
    report_path = output_dir / report_filename
    generated = datetime.now().astimezone().isoformat(timespec="seconds")
    delta_validation = float(validation["validation_accuracy"]["mean"]) - paper_validation
    delta_test = float(test_accuracy["mean"]) - paper_test
    if schema is not None:
        conclusion = (
            f"MediaPipe {token_feature_count}D 적응형 {spec.protocol}는 원 논문보다 낮은 정확도를 보였다. "
            "특히 원 논문의 OpenFace 계열 Gaze·Head Pose·AU와 MediaPipe 랜드마크·블렌드셰이프는 의미가 완전히 같지 않고, "
            "얼굴 검출 실패 제외 및 subject provenance 불일치가 있어 직접적인 수치 동등 재현으로 해석하면 안 된다."
        )
    else:
        conclusion = (
            f"ST-GCN 기반 {spec.protocol}({spec.schema_name})는 원 논문보다 낮은 정확도를 보였다. "
            "원 논문의 OpenFace 계열 Gaze·Head Pose·AU 특징이 아니라 MediaPipe 랜드마크 시퀀스를 "
            "공간-시간 그래프로 학습했으므로 의미가 완전히 같지 않고, "
            "얼굴 검출 실패 제외 및 subject provenance 불일치가 있어 직접적인 수치 동등 재현으로 해석하면 안 된다."
        )
    document = f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>EngageNet {html.escape(spec.protocol)} {title_family} 재현 보고서</title>
<style>
:root{{--bg:#07111f;--panel:#0d1b2d;--panel2:#13243a;--text:#e8f0fa;--muted:#9fb0c4;--accent:#52d3b7;--warn:#ffbf69;--line:#29415e}}
*{{box-sizing:border-box}} body{{margin:0;background:linear-gradient(145deg,#06101c,#0b1930 55%,#102743);color:var(--text);font:15px/1.65 system-ui,-apple-system,"Segoe UI",sans-serif}}
main{{max-width:1180px;margin:auto;padding:46px 24px 80px}} h1{{font-size:clamp(32px,5vw,58px);line-height:1.08;margin:0 0 12px}} h2{{margin:42px 0 14px;font-size:25px}} h3{{margin:24px 0 10px}} p{{color:var(--muted)}} a{{color:#7ee8cf}} .eyebrow{{color:var(--accent);font-weight:700;letter-spacing:.12em}} .hero{{padding:34px;border:1px solid var(--line);border-radius:24px;background:rgba(13,27,45,.88)}}
.kpis{{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:14px;margin:22px 0}} .kpi{{padding:20px;background:var(--panel);border:1px solid var(--line);border-radius:16px}} .kpi span{{display:block;color:var(--muted)}} .kpi strong{{font-size:28px;color:var(--accent)}}
.panel{{padding:22px;background:rgba(13,27,45,.9);border:1px solid var(--line);border-radius:18px;margin:14px 0}} .callout{{border-left:4px solid var(--warn);padding:14px 18px;background:#2a231c;color:#ffe0b5;border-radius:8px}} .table-wrap{{overflow:auto}} table{{width:100%;border-collapse:collapse;background:var(--panel)}} th,td{{padding:11px 12px;border-bottom:1px solid var(--line);text-align:right;white-space:nowrap}} th:first-child,td:first-child{{text-align:left}} th{{color:#b9cbe0;background:var(--panel2)}} code{{white-space:normal;word-break:break-all;color:#b9f7ea}} ul{{color:var(--muted)}} footer{{margin-top:44px;color:var(--muted)}}
</style></head><body><main>
<section class="hero"><div class="eyebrow">ENGAGENET {html.escape(spec.protocol)} · {html.escape(eyebrow_label)}</div><h1>{model_title} 재현 실험 보고서</h1><p>Validation-only 모델 선택 뒤 고정된 5개 체크포인트를 Test에 각 1회 평가했다. 생성: {html.escape(generated)}</p></section>
<section class="kpis"><article class="kpi"><span>학습 가능 클립</span><strong>{len(included):,}</strong></article><article class="kpi"><span>제외 클립</span><strong>{len(excluded):,}</strong></article><article class="kpi"><span>Validation Accuracy</span><strong>{_pct(float(validation['validation_accuracy']['mean']))}</strong></article><article class="kpi"><span>Test Accuracy</span><strong>{_pct(float(test_accuracy['mean']))}</strong></article></section>
<h2>결론</h2><section class="panel"><p>{html.escape(conclusion)}</p></section>
<h2>원 논문과 비교</h2><section class="panel">{_table(['지표','원 논문 Transformer','현재 5-seed 평균','차이'], [['Validation Accuracy',_pct(paper_validation),_mean_std(validation['validation_accuracy']),f'{delta_validation*100:+.2f}%p'],['Test Accuracy',_pct(paper_test),_mean_std(test_accuracy),f'{delta_test*100:+.2f}%p'],['Validation Macro F1','미보고',_mean_std(validation['validation_macro_f1']),'비교 불가'],['Test Macro F1','미보고',_mean_std(test_macro_f1),'비교 불가']])}<p>원 논문의 Gaze + Head Pose + AU Transformer 기준은 Validation {_pct(paper_validation)}, Test {_pct(paper_test)}다.</p></section>
<h2>5-seed 결과</h2><section class="panel">{_table(['Seed','Best epoch','Val Accuracy','Val Macro F1','Test Accuracy','Test Macro F1'],seed_rows)}</section>
<h2>혼동행렬</h2><section class="panel"><p>행=정답, 열=예측. 5개 seed의 Test 혼동행렬을 합산했으므로 각 샘플이 seed별로 5회 포함된다.</p>{_table(['정답 \\ 예측',*LABELS],confusion_rows)}</section>
<h2>클래스별 Test 지표</h2><section class="panel">{_table(['클래스','Precision 평균±표준편차','Recall 평균±표준편차','F1 평균±표준편차','Seed당 support'],class_rows)}</section>
{adaptation_section}
<h2>데이터와 제외</h2><section class="panel">{_table(['Split','포함','제외','합계'],split_rows)}<p>최초 전체 추출은 기본 최대 제외율 5%를 넘겨 실패 상태로 보존됐다: {original_excluded:,}/{original_total:,} ({original_fraction*100:.2f}%). 캐시 재검증 후 최종 포함 {len(included):,}, 제외 {len(excluded):,} ({len(excluded)/len(included+excluded)*100:.2f}%). 제외 사유를 숨기지 않고 아래에 집계했다.</p>{_table(['제외 사유','클립 수'],_counter_rows(excluded_reason))}</section>
<h2>Subject provenance</h2><section class="panel"><div class="callout">공식 설명은 Train 90명·전체 127명이나 현재 제공 데이터는 Train 91명·전체 128명이다. 분할 간 subject 중복은 없지만, 공개 저장소에 원 split 파일이 없어 추가 1명의 정체를 검증하거나 임의 삭제하지 않았다.</div>{_table(['출처','Train','Validation','Test','전체'],provenance_rows)}</section>
<h2>실험 환경</h2><section class="panel">{_table(['항목','값'],environment_rows)}</section>
<h2>모델 무결성</h2><section class="panel">{_table(['Seed','체크포인트','SHA-256'],checkpoint_rows)}</section>
<h2>한계</h2><section class="panel"><ul><li>MediaPipe 특징은 원 논문의 Gaze·Head Pose·AU 입력을 근사하며 동일 구현이 아니다.</li><li>얼굴 검출 또는 커버리지 실패 클립 963개가 제외되어 원 논문과 평가 표본이 다르다.</li><li>공식 공개 설명과 현재 데이터 사이에 Train subject 1명 차이가 있다.</li><li>원 논문은 단일 수치를 보고해 5-seed 분산과 직접 비교할 수 없다.</li><li>Test는 설정 변경에 쓰지 않았고, 5개 Validation 선택 체크포인트를 고정한 뒤 한 번씩만 평가했다.</li></ul></section>
<h2>1차 출처</h2><section class="panel"><ul><li><a href="https://dl.acm.org/doi/10.1145/3577190.3614164">ACM Multimedia 2023 논문</a></li><li><a href="https://arxiv.org/abs/2302.00431">저자 공개 arXiv 원고</a></li><li><a href="https://github.com/engagenet/engagenet_baselines">저자 공식 baseline 저장소</a></li></ul></section>
<footer>산출물: {html.escape(str(report_path.resolve()))}</footer></main></body></html>"""
    _write_text_atomic(report_path, document)
    return report_path


def generate_e0_html_report(
    features_root: Path,
    output_dir: Path,
    *,
    face_landmarker_model: Path | None = None,
    preparation_manifest: Path | None = None,
    threshold_manifest: Path | None = None,
) -> Path:
    """Backward-compatible E0 wrapper; identical output to before generalization."""

    return generate_html_report(
        features_root,
        output_dir,
        spec=E0_SPEC,
        face_landmarker_model=face_landmarker_model,
        preparation_manifest=preparation_manifest,
        threshold_manifest=threshold_manifest,
    )


def finalize_experiment(
    spec: ExperimentSpec,
    features_root: Path,
    output_dir: Path,
    *,
    device: str,
    face_landmarker_model: Path | None = None,
    preparation_manifest: Path | None = None,
    threshold_manifest: Path | None = None,
) -> Path:
    evaluate_frozen_checkpoints(features_root, output_dir, device=device, spec=spec)
    return generate_html_report(
        features_root,
        output_dir,
        spec=spec,
        face_landmarker_model=face_landmarker_model,
        preparation_manifest=preparation_manifest,
        threshold_manifest=threshold_manifest,
    )


def finalize_e0(
    features_root: Path,
    output_dir: Path,
    *,
    device: str,
    face_landmarker_model: Path | None = None,
    preparation_manifest: Path | None = None,
    threshold_manifest: Path | None = None,
) -> Path:
    """Backward-compatible E0 wrapper; identical output to before generalization."""

    return finalize_experiment(
        E0_SPEC,
        features_root,
        output_dir,
        device=device,
        face_landmarker_model=face_landmarker_model,
        preparation_manifest=preparation_manifest,
        threshold_manifest=threshold_manifest,
    )


__all__ = [
    "E0A_SPEC",
    "E0_SPEC",
    "evaluate_frozen_checkpoints",
    "evaluate_frozen_e0_checkpoints",
    "finalize_e0",
    "finalize_experiment",
    "generate_e0_html_report",
    "generate_html_report",
]
