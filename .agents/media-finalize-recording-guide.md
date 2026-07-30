# ZANI Recording Finalize Worker Contract

The finalize worker merges the separate Track Egress source files of a finished
lecture into one `lecture.mp4`. This document is the canonical contract for that
worker: the manifest it accepts, the layout it derives, the output it guarantees,
and the exit codes it returns.

Two independent implementations must agree with this contract:

- `infrastructure/media/finalize-recording.sh` and
  `infrastructure/media/finalize_recording.py` — the worker itself.
- `backend/.../recording/domain/model/RecordingManifest.java` — the producer.
  It enforces the same invariants so the backend never writes a manifest the
  worker would reject.

Change this document and both implementations together. Do not encode merge
rules anywhere else.

## 1. Scope

In scope: `manifest → track validation → timeline alignment → video and audio
merge → output validation → lecture.mp4`.

Out of scope, owned by other work: merge triggering and Egress orchestration,
the recording state machine in the database, lecture recording start/stop, and
transcription. The worker assumes a complete manifest already exists and does
not read or write the database.

The worker never deletes source tracks or the manifest, under any exit path.

## 2. Components and invocation

```text
infrastructure/media/
  finalize-recording.sh    CLI entry point: per-session flock, argument
                           handling, python3 invocation, atomic rename
  finalize_recording.py    manifest parsing and validation, layout derivation,
                           FFmpeg filtergraph construction, merge, validation
  sample-manifest.json     input example
  tests/                   unit tests and an end-to-end script
```

```bash
finalize-recording.sh \
  --manifest /srv/zani/recordings/{sessionId}/manifest/tracks.json \
  --output   /srv/zani/recordings/{sessionId}/final/lecture.mp4
```

`--session-dir` (default: the manifest's parent directory) is the base for
resolving each track's `relative_path`.

The Python module writes `<output>.partial` and never the final path. Only
`finalize-recording.sh` renames a validated `.partial` to `<output>`, so a
readable `lecture.mp4` always means a validated file. On success the module
prints one JSON object to stdout:

```json
{ "session_id": "...", "partial_path": "...", "output_path": "...",
  "total_ms": 0, "sha256": "...", "frames": ["..."] }
```

This worker is not part of the Spring Boot application. The orchestration ticket
calls this CLI rather than reimplementing the merge in Java.

## 3. Input manifest (schema v1)

```json
{
  "schema_version": 1,
  "session_id": "session-uuid",
  "timeline_started_at": "2026-07-24T05:00:00Z",
  "tracks": [
    {
      "participant_identity": "instructor",
      "participant_role": "INSTRUCTOR",
      "source": "SCREEN_SHARE",
      "relative_path": "raw/instructor/instructor-screen_share-TR_xxx.webm",
      "offset_ms": 1200,
      "duration_ms": 10000,
      "sha256": "optional-sha256"
    }
  ]
}
```

- `schema_version` must be exactly `1`.
- `session_id` and `timeline_started_at` are required and must be non-empty.
- `tracks` must be a non-empty array.
- `source` enum: `CAMERA`, `MICROPHONE`, `SCREEN_SHARE`, `SCREEN_SHARE_AUDIO`.
- `participant_role` enum: `INSTRUCTOR`, `STUDENT`.
- `participant_identity` is required and must already be an anonymised alias
  (`instructor`, `student-001`, ...). Never a real name, email, or user ID.
- `offset_ms` must be a non-negative integer, `duration_ms` a positive integer.
  Booleans are rejected — they are not accepted as integers.
- `offset_ms` is relative to `timeline_started_at`, which is 0 ms of the final
  video. A track occupies `[offset_ms, offset_ms + duration_ms)`.
- A reconnect or re-publish is a separate array entry even for the same
  participant and source. There is no `segment_id`; one array entry is one
  segment.
- `sha256` is verified when present and skipped when absent.
- `duration_ms` is compared against `ffprobe`. A mismatch beyond
  `DURATION_TOLERANCE_MS` (1500 ms) is a warning, not a failure.
- A `relative_path` that is absolute or escapes the session root is rejected.
  Resolution happens after following the path, so `..` cannot escape.
- A student `CAMERA` track is excluded with a warning and the merge continues.

## 4. Layout, derived from instructor screen-share segments

There is no separate layout timeline. The layout follows the instructor's
**video `SCREEN_SHARE` segments** only; `SCREEN_SHARE_AUDIO` never affects it.

```text
INSTRUCTOR SCREEN_SHARE segment → screen share as main + instructor CAMERA as
                                  bottom-right PiP (320x180, 24 px margin)
every other interval            → instructor CAMERA as main (1280x720 letterbox)
```

Only instructor video is rendered. Student cameras are never drawn, and student
screen shares are not part of the layout derivation.

Behaviour at the edges:

- Screen share present, no instructor camera → screen share only.
- **No instructor video at all → the worker fails with exit `3`.** There is no
  black-screen fallback. The black canvas exists only as the base layer that
  overlays composite onto.
- Overlapping instructor screen-share segments → manifest error, exit `2`.

Rationale: tracking screen-share time and layout-switch time separately lets
them drift. The recorded track segments are the single source of truth. If
multiple sharers or manual layout switching become necessary, add
`layout_events` in schema v2 rather than a parallel timeline.

## 5. Audio

- Mixed: instructor `MICROPHONE`, each anonymised student `MICROPHONE`, and
  `SCREEN_SHARE_AUDIO`. Every track whose source is an audio source is mixed,
  regardless of role.
- Per track `adelay` aligns `offset_ms`. `SCREEN_SHARE_AUDIO` is attenuated to
  `volume=0.35` so it does not bury speech.
- Mixing is `amix=inputs=N:duration=longest:dropout_transition=2:normalize=0`,
  then `alimiter=limit=0.95` to prevent clipping, then `apad` and `atrim` to the
  full timeline length.
- `normalize=0` is required. The `amix` default lowers volume as input count
  grows; a 16-input mix produced audibly quiet output before this was fixed.
- No audio tracks in the manifest at all → a warning and a silent output track
  (`anullsrc`, 48 kHz stereo). Not a failure.
- A manifest entry whose file is missing on disk is always exit `4`, audio
  included. Only *absence from the manifest* is tolerated, not a broken entry.

## 6. Output and validation

- Output: `1280x720`, `30 fps`, H.264 video + AAC audio, MP4.
- Encode settings: `libx264 -preset veryfast -crf 23 -pix_fmt yuv420p`,
  `aac -b:a 160k -ar 48000`, `-movflags +faststart`. The muxer is forced with
  `-f mp4` because the `.partial` extension is not inferable.
- Total length is `max(offset_ms + duration_ms)` over all tracks.
- Track Egress WebM/Ogg output can carry discontinuous timestamps, so each
  track is decoded to raw (`yuv420p` video, `s16le` PCM audio) and reassembled
  rather than merged directly.
- Validation before rename, all of which must pass:
  - `ffprobe` confirms codec `h264` / `aac` and resolution exactly `1280x720`.
  - Output duration is within `DURATION_TOLERANCE_MS` (1500 ms) of expected.
  - **Full-file decode with no errors** (`ffmpeg -v error -i out -f null -`).
    Any stderr output fails validation. This is the "playable" criterion.
  - SHA-256 of the output is computed and reported.
- Representative PNG frames are extracted to `<output_dir>/frames/` at 5 %,
  25 %, 50 %, 75 %, and 95 % as `frame-05.png` ... `frame-95.png`. This is
  best-effort QA support; a failed extraction only warns.

## 7. Operational safety

- **No concurrent runs**: non-blocking per-session `flock`. If a merge is
  already running the worker exits `9` without merging.
- **Sources preserved**: source tracks and the manifest are never deleted.
- **Retry**: a failed run deletes its `.partial` and a re-run starts over. The
  worker never resumes from a partial artifact. Job state and leases belong to
  the orchestration ticket, not here.
- The temporary raw-decode directory is always removed, including on failure.

## 8. Exit codes

| Code | Meaning |
| --- | --- |
| `0` | final MP4 produced and validated |
| `2` | manifest error (schema, enum, missing field, path escape, overlapping screen-share segments) |
| `3` | no required video (no instructor camera and no instructor screen share) |
| `4` | input track validation failed (missing file, sha256 mismatch, ffprobe failure) |
| `5` | FFmpeg merge or decode failed |
| `6` | final MP4 validation failed |
| `9` | lock not acquired (a merge is already running) |
| `64` | CLI usage error (missing or unknown argument) — produced by the shell wrapper only |

Missing optional audio *entries* do not affect the exit code; a run can warn and
still exit `0`.

## 9. Open item

`livekit-backend-guide.md` §15 requires the merge to **fail with a security
error** when a student camera file appears in the manifest. This worker
**excludes it with a warning and continues**. The stricter posture has not been
agreed, so the worker's behaviour above is what ships today. Resolve this before
relying on either rule, and update both documents together.
