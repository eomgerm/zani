#!/usr/bin/env bash
#
# finalize-recording end-to-end 테스트 (S15P11A105-176).
# 합성 트랙(webm/ogg) + manifest 를 만들어 실제 병합·검증을 수행한다.
# ffmpeg/ffprobe/flock 이 없으면 SKIP(exit 0) 한다. (로컬 Windows 에는 없음 → EC2 에서 실행)

set -euo pipefail

for tool in ffmpeg ffprobe flock python3; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "SKIP: '$tool' not found (run this on EC2 where ffmpeg/flock exist)"
    exit 0
  fi
done

media_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
session_dir=$(mktemp -d /tmp/zani-e2e-XXXXXX)
raw="$session_dir/raw"
mkdir -p "$raw" "$session_dir/final"

cleanup() { rm -rf -- "$session_dir"; }
trap cleanup EXIT

echo "e2e session dir: $session_dir"

# 합성 입력: 카메라 0~6s, 화면공유 2~4s, 강사 마이크 0~6s, 학생 마이크 1~4s, 화면오디오 2~4s
ffmpeg -y -hide_banner -loglevel error -f lavfi -i "testsrc=size=640x480:rate=25:duration=6" \
  -c:v libvpx -b:v 400k "$raw/instructor-camera-001.webm"
ffmpeg -y -hide_banner -loglevel error -f lavfi -i "testsrc2=size=1280x720:rate=15:duration=2" \
  -c:v libvpx -b:v 600k "$raw/instructor-screen-001.webm"
ffmpeg -y -hide_banner -loglevel error -f lavfi -i "sine=frequency=440:duration=6" \
  -c:a libvorbis "$raw/instructor-mic-001.ogg"
ffmpeg -y -hide_banner -loglevel error -f lavfi -i "sine=frequency=660:duration=3" \
  -c:a libvorbis "$raw/student-001-mic-001.ogg"
ffmpeg -y -hide_banner -loglevel error -f lavfi -i "sine=frequency=220:duration=2" \
  -c:a libvorbis "$raw/instructor-screen-audio-001.ogg"
sha256sum "$raw"/* > "$session_dir/source.sha256"

cat > "$session_dir/manifest.json" <<'JSON'
{
  "schema_version": 1,
  "session_id": "e2e-session",
  "timeline_started_at": "2026-07-24T05:00:00Z",
  "tracks": [
    { "participant_identity": "instructor", "participant_role": "INSTRUCTOR", "source": "CAMERA",
      "relative_path": "raw/instructor-camera-001.webm", "offset_ms": 0, "duration_ms": 6000 },
    { "participant_identity": "instructor", "participant_role": "INSTRUCTOR", "source": "SCREEN_SHARE",
      "relative_path": "raw/instructor-screen-001.webm", "offset_ms": 2000, "duration_ms": 2000 },
    { "participant_identity": "instructor", "participant_role": "INSTRUCTOR", "source": "MICROPHONE",
      "relative_path": "raw/instructor-mic-001.ogg", "offset_ms": 0, "duration_ms": 6000 },
    { "participant_identity": "student-001", "participant_role": "STUDENT", "source": "MICROPHONE",
      "relative_path": "raw/student-001-mic-001.ogg", "offset_ms": 1000, "duration_ms": 3000 },
    { "participant_identity": "instructor", "participant_role": "INSTRUCTOR", "source": "SCREEN_SHARE_AUDIO",
      "relative_path": "raw/instructor-screen-audio-001.ogg", "offset_ms": 2000, "duration_ms": 2000 }
  ]
}
JSON

output="$session_dir/final/lecture.mp4"
lock_dir="$session_dir/locks"
mkdir -p "$lock_dir"
bash "$media_dir/finalize-recording.sh" \
  --manifest "$session_dir/manifest.json" \
  --session-dir "$session_dir" \
  --lock-dir "$lock_dir" \
  --output "$output"

# 검증
[[ -f "$output" ]] || { echo "FAIL: output not created"; exit 1; }
[[ ! -f "$output.partial" ]] || { echo "FAIL: partial not renamed"; exit 1; }
[[ -f "$lock_dir/.finalize.lock" ]] || { echo "FAIL: lock was not created in --lock-dir"; exit 1; }
[[ "$(stat -c '%a' "$output")" == "640" ]] || { echo "FAIL: output mode is not 0640"; exit 1; }
sha256sum --check --status "$session_dir/source.sha256" || { echo "FAIL: source track changed"; exit 1; }

probe() { ffprobe -v error "$@" -of default=noprint_wrappers=1:nokey=1 "$output"; }
vcodec=$(probe -select_streams v:0 -show_entries stream=codec_name)
width=$(probe -select_streams v:0 -show_entries stream=width)
height=$(probe -select_streams v:0 -show_entries stream=height)
acodec=$(probe -select_streams a:0 -show_entries stream=codec_name)
duration=$(probe -show_entries format=duration)

echo "output: vcodec=$vcodec ${width}x${height} acodec=$acodec duration=${duration}s"
[[ "$vcodec" == "h264" ]] || { echo "FAIL: video codec $vcodec != h264"; exit 1; }
[[ "$acodec" == "aac" ]] || { echo "FAIL: audio codec $acodec != aac"; exit 1; }
[[ "$width" == "1280" && "$height" == "720" ]] || { echo "FAIL: resolution ${width}x${height}"; exit 1; }

echo "PASS: finalize-recording e2e"
