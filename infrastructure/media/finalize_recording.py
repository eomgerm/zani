#!/usr/bin/env python3
"""ZANI 강의 녹화 후처리 병합 (S15P11A105-176).

Track Egress 원본 트랙 + manifest 를 입력받아 시간축을 정렬하고 FFmpeg 로 병합해
최종 lecture.mp4(.partial) 을 생성·검증한다. 실제 원자적 rename 과 flock 은
finalize-recording.sh 래퍼가 담당한다.

FFmpeg 병합 기법은 S15P11A105-54 smoke(tmp/final-events-merge.sh)에서 검증한 것을
manifest 기반으로 일반화했다. 계약: .agents/media-finalize-recording-guide.md
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

# --- 출력·레이아웃 상수 ---
OUT_W, OUT_H, FPS = 1280, 720, 30
PIP_W, PIP_H, PIP_MARGIN = 320, 180, 24
SCREEN_AUDIO_VOLUME = 0.35
AUDIO_RATE = 48000

VIDEO_SOURCES = {"CAMERA", "SCREEN_SHARE"}
AUDIO_SOURCES = {"MICROPHONE", "SCREEN_SHARE_AUDIO"}
SOURCES = VIDEO_SOURCES | AUDIO_SOURCES
ROLES = {"INSTRUCTOR", "STUDENT"}

# --- 종료 코드 (.agents/media-finalize-recording-guide.md §8) ---
EXIT_OK = 0
EXIT_MANIFEST = 2
EXIT_NO_VIDEO = 3
EXIT_INPUT = 4
EXIT_FFMPEG = 5
EXIT_VALIDATE = 6

DURATION_TOLERANCE_MS = 1500


class WorkerError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code


def log(message: str) -> None:
    print(f"[finalize-recording] {message}", file=sys.stderr)


def warn(message: str) -> None:
    print(f"[finalize-recording][WARN] {message}", file=sys.stderr)


# ============================================================
# 순수 로직 (ffmpeg 불필요 · 단위테스트 대상)
# ============================================================

def load_manifest(path: str) -> dict:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except FileNotFoundError as exc:
        raise WorkerError(EXIT_MANIFEST, f"manifest not found: {path}") from exc
    except json.JSONDecodeError as exc:
        raise WorkerError(EXIT_MANIFEST, f"manifest is not valid JSON: {exc}") from exc


def resolve_track_path(session_dir: str, relative_path: str) -> Path:
    """세션 루트 밖으로 나가는 경로(절대경로·`..` 탈출)를 거부한다."""
    if relative_path is None or relative_path == "":
        raise WorkerError(EXIT_MANIFEST, "track relative_path is empty")
    if os.path.isabs(relative_path) or relative_path.startswith("\\"):
        raise WorkerError(EXIT_MANIFEST, f"relative_path must be relative: {relative_path}")
    root = Path(session_dir).resolve()
    resolved = (root / relative_path).resolve()
    if resolved != root and root not in resolved.parents:
        raise WorkerError(EXIT_MANIFEST, f"relative_path escapes session dir: {relative_path}")
    return resolved


def validate_manifest(manifest: dict, session_dir: str) -> list[dict]:
    """manifest 를 검증하고 정규화된 track 목록을 반환한다."""
    if not isinstance(manifest, dict):
        raise WorkerError(EXIT_MANIFEST, "manifest must be a JSON object")
    if manifest.get("schema_version") != 1:
        raise WorkerError(EXIT_MANIFEST, f"unsupported schema_version: {manifest.get('schema_version')!r}")
    for field in ("session_id", "timeline_started_at"):
        if not manifest.get(field):
            raise WorkerError(EXIT_MANIFEST, f"manifest missing required field: {field}")
    raw_tracks = manifest.get("tracks")
    if not isinstance(raw_tracks, list) or not raw_tracks:
        raise WorkerError(EXIT_MANIFEST, "manifest.tracks must be a non-empty array")

    tracks: list[dict] = []
    for index, raw in enumerate(raw_tracks):
        where = f"tracks[{index}]"
        if not isinstance(raw, dict):
            raise WorkerError(EXIT_MANIFEST, f"{where} must be an object")
        role = raw.get("participant_role")
        source = raw.get("source")
        identity = raw.get("participant_identity")
        if role not in ROLES:
            raise WorkerError(EXIT_MANIFEST, f"{where}.participant_role invalid: {role!r}")
        if source not in SOURCES:
            raise WorkerError(EXIT_MANIFEST, f"{where}.source invalid: {source!r}")
        if not identity:
            raise WorkerError(EXIT_MANIFEST, f"{where}.participant_identity is required")
        offset_ms = _require_non_negative_int(raw.get("offset_ms"), f"{where}.offset_ms")
        duration_ms = _require_positive_int(raw.get("duration_ms"), f"{where}.duration_ms")
        path = resolve_track_path(session_dir, raw.get("relative_path"))
        tracks.append({
            "identity": identity,
            "role": role,
            "source": source,
            "relative_path": raw.get("relative_path"),
            "path": path,
            "offset_ms": offset_ms,
            "duration_ms": duration_ms,
            "sha256": raw.get("sha256"),
        })

    # 학생 카메라는 항상 제외한다. manifest 에 존재하면 배제하고 경고한다.
    # (livekit-backend-guide.md §15 는 보안 오류로 실패 처리도 규정하나, 176 범위에서는 '항상 제외' 규칙을 따른다.)
    kept = []
    for track in tracks:
        if track["role"] == "STUDENT" and track["source"] == "CAMERA":
            warn(f"excluding student camera track (never rendered): {track['relative_path']}")
            continue
        kept.append(track)
    return kept


def _require_non_negative_int(value, where: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise WorkerError(EXIT_MANIFEST, f"{where} must be a non-negative integer")
    return value


def _require_positive_int(value, where: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise WorkerError(EXIT_MANIFEST, f"{where} must be a positive integer")
    return value


def total_duration_ms(tracks: list[dict]) -> int:
    return max(t["offset_ms"] + t["duration_ms"] for t in tracks)


def instructor_screen_intervals(tracks: list[dict]) -> list[tuple[int, int]]:
    """강사 SCREEN_SHARE 영상 구간을 [start,end) 로 정렬 반환. 겹치면 오류."""
    intervals = sorted(
        (t["offset_ms"], t["offset_ms"] + t["duration_ms"])
        for t in tracks
        if t["role"] == "INSTRUCTOR" and t["source"] == "SCREEN_SHARE"
    )
    for (a_start, a_end), (b_start, b_end) in zip(intervals, intervals[1:]):
        if b_start < a_end:
            raise WorkerError(
                EXIT_MANIFEST,
                f"overlapping screen_share intervals not supported: "
                f"[{a_start},{a_end}) and [{b_start},{b_end})",
            )
    return intervals


def select_video_tracks(tracks: list[dict]) -> list[dict]:
    """레이아웃 영상: 강사 SCREEN_SHARE(먼저) + 강사 CAMERA(뒤), 각각 offset 순."""
    screens = sorted(
        (t for t in tracks if t["role"] == "INSTRUCTOR" and t["source"] == "SCREEN_SHARE"),
        key=lambda t: t["offset_ms"],
    )
    cameras = sorted(
        (t for t in tracks if t["role"] == "INSTRUCTOR" and t["source"] == "CAMERA"),
        key=lambda t: t["offset_ms"],
    )
    return screens + cameras


def select_audio_tracks(tracks: list[dict]) -> list[dict]:
    """혼합 오디오: 모든 MICROPHONE + SCREEN_SHARE_AUDIO (manifest 순서 유지)."""
    return [t for t in tracks if t["source"] in AUDIO_SOURCES]


def has_required_video(video_tracks: list[dict]) -> bool:
    return len(video_tracks) > 0


def _fmt_s(ms: int) -> str:
    return f"{ms / 1000:.3f}"


def build_filter_complex(
    video_tracks: list[dict],
    audio_tracks: list[dict],
    screen_intervals: list[tuple[int, int]],
    total_ms: int,
) -> str:
    """FFmpeg filter_complex 문자열을 생성한다 (순수 함수, 입력 인덱스는 위치 순).

    입력 순서: 영상 트랙(video_tracks 순) 다음 오디오 트랙(audio_tracks 순).
    화면공유 구간에는 화면 메인 + 강사 카메라 우하단 PiP, 그 외 구간에는 강사 카메라 메인.
    """
    total_s = _fmt_s(total_ms)
    parts: list[str] = [f"color=c=black:s={OUT_W}x{OUT_H}:r={FPS}:d={total_s}[vb0]"]
    current = "vb0"
    step = 0

    screen_sum = "+".join(
        f"between(t,{_fmt_s(s)},{_fmt_s(e)})" for s, e in screen_intervals
    ) or "0"

    # 화면 공유 오버레이 (구간 gating)
    for pos, track in enumerate(video_tracks):
        if track["source"] != "SCREEN_SHARE":
            continue
        start = track["offset_ms"]
        end = track["offset_ms"] + track["duration_ms"]
        scaled = f"scr{pos}"
        parts.append(
            f"[{pos}:v]setpts=PTS-STARTPTS+{_fmt_s(start)}/TB,"
            f"scale={OUT_W}:{OUT_H}:force_original_aspect_ratio=decrease,"
            f"pad={OUT_W}:{OUT_H}:(ow-iw)/2:(oh-ih)/2:black[{scaled}]"
        )
        nxt = f"vlay{step}"
        step += 1
        parts.append(
            f"[{current}][{scaled}]overlay=0:0:"
            f"enable='between(t,{_fmt_s(start)},{_fmt_s(end)})':eof_action=pass[{nxt}]"
        )
        current = nxt

    # 강사 카메라 오버레이 (화면 없으면 메인, 화면 있으면 PiP)
    for pos, track in enumerate(video_tracks):
        if track["source"] != "CAMERA":
            continue
        start = track["offset_ms"]
        end = track["offset_ms"] + track["duration_ms"]
        base = f"cam{pos}"
        parts.append(
            f"[{pos}:v]setpts=PTS-STARTPTS+{_fmt_s(start)}/TB,split=2[{base}a][{base}b]"
        )
        parts.append(
            f"[{base}a]scale={OUT_W}:{OUT_H}:force_original_aspect_ratio=decrease,"
            f"pad={OUT_W}:{OUT_H}:(ow-iw)/2:(oh-ih)/2:black[{base}main]"
        )
        parts.append(
            f"[{base}b]scale={PIP_W}:{PIP_H}:force_original_aspect_ratio=decrease,"
            f"pad={PIP_W}:{PIP_H}:(ow-iw)/2:(oh-ih)/2:black[{base}pip]"
        )
        window = f"between(t,{_fmt_s(start)},{_fmt_s(end)})"
        main_enable = f"{window}*lt({screen_sum},1)"
        nxt = f"vlay{step}"
        step += 1
        parts.append(
            f"[{current}][{base}main]overlay=(main_w-overlay_w)/2:(main_h-overlay_h)/2:"
            f"enable='{main_enable}':eof_action=pass[{nxt}]"
        )
        current = nxt
        pip_enable = f"{window}*gte({screen_sum},1)"
        nxt = f"vlay{step}"
        step += 1
        parts.append(
            f"[{current}][{base}pip]overlay=main_w-overlay_w-{PIP_MARGIN}:main_h-overlay_h-{PIP_MARGIN}:"
            f"enable='{pip_enable}':eof_action=pass[{nxt}]"
        )
        current = nxt

    parts.append(f"[{current}]fps={FPS}[v]")

    # 오디오 혼합
    n_video = len(video_tracks)
    if audio_tracks:
        labels = []
        for q, track in enumerate(audio_tracks):
            global_index = n_video + q
            offset = track["offset_ms"]
            label = f"a{q}"
            chain = f"[{global_index}:a]adelay={offset}|{offset}"
            if track["source"] == "SCREEN_SHARE_AUDIO":
                chain += f",volume={SCREEN_AUDIO_VOLUME}"
            chain += f"[{label}]"
            parts.append(chain)
            labels.append(f"[{label}]")
        # normalize=0: amix 기본 정규화가 입력 수가 많을수록 음량을 낮추는 문제를 막는다.
        # (phase-5-report: 16-input amix 정규화로 오디오가 작게 생성된 문제를 이렇게 고쳤다.)
        parts.append(
            "".join(labels)
            + f"amix=inputs={len(audio_tracks)}:duration=longest:dropout_transition=2:normalize=0,"
            f"alimiter=limit=0.95,apad,atrim=duration={total_s}[a]"
        )
    else:
        warn("no audio tracks present; output will be silent")
        parts.append(f"anullsrc=r={AUDIO_RATE}:cl=stereo,atrim=duration={total_s}[a]")

    return ";".join(parts)


# ============================================================
# 실행 (ffmpeg/ffprobe 필요)
# ============================================================

def _run(cmd: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True)


def verify_sha256(path: Path, expected: str | None) -> None:
    if not expected:
        return
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    actual = digest.hexdigest()
    if actual != expected:
        raise WorkerError(EXIT_INPUT, f"sha256 mismatch for {path.name}: expected {expected}, got {actual}")


def ffprobe_dimensions(path: Path) -> tuple[int, int]:
    result = _run([
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream=width,height", "-of", "csv=p=0:s=x", str(path),
    ])
    if result.returncode != 0 or "x" not in result.stdout:
        raise WorkerError(EXIT_INPUT, f"ffprobe failed to read video dimensions: {path.name}: {result.stderr.strip()}")
    width, height = result.stdout.strip().split("x")[:2]
    return int(width), int(height)


def ffprobe_duration_ms(path: Path) -> int:
    result = _run([
        "ffprobe", "-v", "error", "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1", str(path),
    ])
    if result.returncode != 0 or not result.stdout.strip():
        raise WorkerError(EXIT_INPUT, f"ffprobe failed to read duration: {path.name}: {result.stderr.strip()}")
    return int(round(float(result.stdout.strip()) * 1000))


def decode_video(path: Path, out_raw: Path, duration_ms: int) -> tuple[int, int, str]:
    """영상 트랙을 raw yuv420p 로 디코드하고 (width, height, framerate 분수)를 반환한다."""
    width, height = ffprobe_dimensions(path)
    result = _run([
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-i", str(path), "-map", "0:v:0", "-fps_mode", "passthrough",
        "-pix_fmt", "yuv420p", "-f", "rawvideo", str(out_raw),
    ])
    if result.returncode != 0:
        raise WorkerError(EXIT_FFMPEG, f"decode video failed: {path.name}: {result.stderr.strip()}")
    frame_bytes = width * height * 3 // 2
    frames = out_raw.stat().st_size // frame_bytes if frame_bytes else 0
    if frames <= 0:
        raise WorkerError(EXIT_INPUT, f"decoded video has no frames: {path.name}")
    # framerate = frames / duration(s) 를 정수 분수로 표현 (smoke 기법)
    framerate = f"{frames * 1000}/{duration_ms}"
    return width, height, framerate


def decode_audio(path: Path, out_raw: Path) -> None:
    result = _run([
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-i", str(path), "-map", "0:a:0", "-ac", "2", "-ar", str(AUDIO_RATE),
        "-f", "s16le", str(out_raw),
    ])
    if result.returncode != 0:
        raise WorkerError(EXIT_FFMPEG, f"decode audio failed: {path.name}: {result.stderr.strip()}")


def validate_output(path: Path, expected_total_ms: int) -> str:
    """최종 MP4 검증: codec(H.264/AAC)·해상도·duration·재생가능. sha256 반환."""
    result = _run([
        "ffprobe", "-v", "error", "-show_entries",
        "stream=codec_type,codec_name,width,height:format=duration",
        "-of", "json", str(path),
    ])
    if result.returncode != 0:
        raise WorkerError(EXIT_VALIDATE, f"output not playable: {result.stderr.strip()}")
    probe = json.loads(result.stdout)
    streams = probe.get("streams", [])
    video = next((s for s in streams if s.get("codec_type") == "video"), None)
    audio = next((s for s in streams if s.get("codec_type") == "audio"), None)
    if not video or video.get("codec_name") != "h264":
        raise WorkerError(EXIT_VALIDATE, f"output video codec is not h264: {video}")
    if not audio or audio.get("codec_name") != "aac":
        raise WorkerError(EXIT_VALIDATE, f"output audio codec is not aac: {audio}")
    if (video.get("width"), video.get("height")) != (OUT_W, OUT_H):
        raise WorkerError(EXIT_VALIDATE, f"output resolution is not {OUT_W}x{OUT_H}: {video.get('width')}x{video.get('height')}")
    duration_ms = int(round(float(probe.get("format", {}).get("duration", 0)) * 1000))
    if abs(duration_ms - expected_total_ms) > DURATION_TOLERANCE_MS:
        raise WorkerError(EXIT_VALIDATE, f"output duration {duration_ms}ms differs from expected {expected_total_ms}ms")

    # 전체 파일 decode 무오류 확인(= "재생 가능" 판정, phase-5 검증 방식과 동일).
    decode = _run(["ffmpeg", "-v", "error", "-i", str(path), "-f", "null", "-"])
    if decode.returncode != 0 or decode.stderr.strip():
        raise WorkerError(EXIT_VALIDATE, f"full decode reported errors: {decode.stderr.strip()[:500]}")

    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def extract_representative_frames(path: Path, out_dir: Path, total_ms: int) -> list[str]:
    """레이아웃 육안 확인용 대표 프레임(PNG)을 추출한다(QA 보조, best-effort).

    phase-4/5 에서 최종 영상의 화면 메인/강사 메인/PiP 전환을 대표 프레임으로 확인했다.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    total_s = total_ms / 1000
    frames: list[str] = []
    for fraction in (0.05, 0.25, 0.5, 0.75, 0.95):
        timestamp = max(0.0, min(total_s - 0.1, total_s * fraction))
        target = out_dir / f"frame-{int(fraction * 100):02d}.png"
        result = _run([
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-ss", f"{timestamp:.3f}", "-i", str(path), "-frames:v", "1", str(target),
        ])
        if result.returncode == 0 and target.exists():
            frames.append(str(target))
        else:
            warn(f"failed to extract representative frame at {timestamp:.3f}s")
    return frames


def merge(manifest_path: str, output_path: str, session_dir: str | None) -> dict:
    session_dir = session_dir or os.path.dirname(os.path.abspath(manifest_path))
    manifest = load_manifest(manifest_path)
    tracks = validate_manifest(manifest, session_dir)

    video_tracks = select_video_tracks(tracks)
    audio_tracks = select_audio_tracks(tracks)
    if not has_required_video(video_tracks):
        raise WorkerError(EXIT_NO_VIDEO, "no instructor camera or screen_share track to render")

    screen_intervals = instructor_screen_intervals(tracks)
    total_ms = total_duration_ms(tracks)
    log(f"session={manifest['session_id']} tracks={len(tracks)} "
        f"video={len(video_tracks)} audio={len(audio_tracks)} total={total_ms}ms "
        f"screen_intervals={screen_intervals}")

    # 입력 무결성 검사
    for track in tracks:
        if not track["path"].exists():
            raise WorkerError(EXIT_INPUT, f"track file missing: {track['relative_path']}")
        verify_sha256(track["path"], track["sha256"])
        probed = ffprobe_duration_ms(track["path"])
        if abs(probed - track["duration_ms"]) > DURATION_TOLERANCE_MS:
            warn(f"{track['relative_path']} duration_ms={track['duration_ms']} "
                 f"differs from ffprobe {probed}ms")

    filter_complex = build_filter_complex(video_tracks, audio_tracks, screen_intervals, total_ms)

    partial = Path(output_path + ".partial")
    partial.parent.mkdir(parents=True, exist_ok=True)
    work_dir = Path(tempfile.mkdtemp(prefix="zani-finalize-"))
    try:
        inputs: list[str] = []
        for pos, track in enumerate(video_tracks):
            raw = work_dir / f"v{pos}.yuv"
            width, height, framerate = decode_video(track["path"], raw, track["duration_ms"])
            inputs += ["-f", "rawvideo", "-pixel_format", "yuv420p",
                       "-video_size", f"{width}x{height}", "-framerate", framerate, "-i", str(raw)]
        for q, track in enumerate(audio_tracks):
            raw = work_dir / f"a{q}.pcm"
            decode_audio(track["path"], raw)
            inputs += ["-f", "s16le", "-ar", str(AUDIO_RATE), "-ac", "2", "-i", str(raw)]

        cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "warning", *inputs,
               "-filter_complex", filter_complex, "-map", "[v]", "-map", "[a]",
               "-t", _fmt_s(total_ms), "-r", str(FPS),
               "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
               "-pix_fmt", "yuv420p", "-threads", "2",
               "-c:a", "aac", "-b:a", "160k", "-ar", str(AUDIO_RATE),
               # 출력 확장자가 .partial 이라 muxer 를 추론할 수 없으므로 mp4 를 명시한다.
               "-movflags", "+faststart", "-f", "mp4", str(partial)]
        result = _run(cmd)
        if result.returncode != 0:
            raise WorkerError(EXIT_FFMPEG, f"ffmpeg merge failed: {result.stderr.strip()[-2000:]}")

        sha256 = validate_output(partial, total_ms)
        frames = extract_representative_frames(partial, partial.parent / "frames", total_ms)
        log(f"partial validated: {partial} sha256={sha256} frames={len(frames)}")
        return {
            "session_id": manifest["session_id"],
            "partial_path": str(partial),
            "output_path": output_path,
            "total_ms": total_ms,
            "sha256": sha256,
            "frames": frames,
        }
    except Exception:
        if partial.exists():
            partial.unlink()
        raise
    finally:
        shutil.rmtree(work_dir, ignore_errors=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="ZANI 강의 녹화 후처리 병합 (S15P11A105-176)")
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--output", required=True, help="최종 산출물 경로. 실제 파일은 <output>.partial 로 생성한다.")
    parser.add_argument("--session-dir", default=None, help="relative_path 해석 기준(기본: manifest 상위 디렉터리)")
    args = parser.parse_args(argv)

    try:
        result = merge(args.manifest, args.output, args.session_dir)
    except WorkerError as exc:
        log(f"FAILED (exit {exc.code}): {exc}")
        return exc.code
    print(json.dumps(result))
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
