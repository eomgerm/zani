# 강의 녹화 후처리 병합 (finalize-recording) 설계

기준 시각: 2026-07-24 · Jira S15P11A105-176

Track Egress로 개별 저장된 원본 트랙을 강의 종료 후 하나의 최종 `lecture.mp4`로 병합하는 **운영 Worker**의 설계다. FFmpeg 병합 기법 자체는 S15P11A105-54(smoke)에서 검증됐고, 이 작업은 그 일회성 스크립트를 **여러 강의에서 재사용 가능한 CLI**로 일반화하고 멱등성·검증을 추가한다.

## 1. 범위

포함: `manifest 입력 → 트랙 검사 → 시간축 정렬 → 영상·오디오 병합 → 결과 검증 → lecture.mp4`.

제외(다른 일감): 병합 트리거·Egress orchestration·DB 상태머신(S15P11A105-68), 수업 녹화 시작·종료(S15P11A105-15), 전사(S15P11A105-95). 이 Worker는 **완성된 manifest가 주어졌다고 가정**하고 동작한다.

## 2. 구성

- `infrastructure/media/finalize-recording.sh` — CLI 진입점. 세션별 `flock`, 인자 처리, `python3` 호출, `.partial → lecture.mp4` atomic rename, 종료 코드.
- `infrastructure/media/finalize_recording.py` — manifest 파싱·검증, 레이아웃 구간 도출, FFmpeg filtergraph 생성, 병합 실행, 결과 검증.

Java Spring backend에는 넣지 않는다. Scheduler·내부 API·DB 연동은 68에서 이 CLI를 호출하는 형태로 연결한다.

### 실행 예

```bash
finalize-recording.sh \
  --manifest /srv/zani/recordings/{sessionId}/manifest.json \
  --output   /srv/zani/recordings/{sessionId}/final/lecture.mp4
```

`--session-dir`(기본: manifest의 상위 디렉터리)를 기준으로 트랙의 `relative_path`를 해석한다.

## 3. 입력 manifest (schema v1)

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
      "relative_path": "raw/instructor-screen-001.webm",
      "offset_ms": 1200,
      "duration_ms": 10000,
      "sha256": "optional-sha256"
    }
  ]
}
```

- `source` enum: `CAMERA`, `MICROPHONE`, `SCREEN_SHARE`, `SCREEN_SHARE_AUDIO`.
- `participant_role` enum: `INSTRUCTOR`, `STUDENT`.
- `offset_ms`는 `timeline_started_at`(최종 영상의 0ms) 기준. 구간은 `[offset_ms, offset_ms + duration_ms)`.
- 재접속·재발행은 같은 participant/source라도 **배열에 별도 항목(segment)**으로 기록한다. 별도 `segment_id`는 두지 않고 배열 항목 하나가 segment 하나다.
- 학생 `participant_identity`는 이미 익명화된 값이어야 한다.
- `sha256`이 있으면 반드시 검증하고, 없으면 건너뛴다.
- `duration_ms`는 `ffprobe` 결과와 대조한다(허용 오차 내).
- 세션 루트 밖으로 나가는 `relative_path`(절대경로·`..` 탈출)는 거부한다.

## 4. 레이아웃 (화면공유 segment 기반 자동 도출)

별도 레이아웃 타임라인을 두지 않는다. 강사의 **영상 `SCREEN_SHARE` segment 구간**만으로 판정한다(레이아웃 판정에 `SCREEN_SHARE_AUDIO`는 쓰지 않는다).

```
INSTRUCTOR SCREEN_SHARE 구간 → 화면 공유 메인 + 강사 CAMERA 우하단 PiP(320x180, 24px 여백)
그 외 구간                   → 강사 CAMERA 메인(1280x720 letterbox)
```

fallback:

- 화면 공유 있음 + 강사 카메라 없음 → 화면 공유만 표시
- 화면 공유 없음 + 강사 카메라 없음 → 검정 화면
- 학생 카메라 → 항상 제외
- 동시에 여러 화면 공유(구간 겹침) → manifest 오류로 실패

이유: `screen_share` 트랙 시간과 레이아웃 전환 시간을 이중 관리하면 어긋날 수 있으므로, 실제 녹화 트랙 segment를 단일 기준으로 삼는다. 다중 공유자·수동 레이아웃 전환이 필요해지면 schema v2에 `layout_events`를 추가한다.

## 5. 오디오

- 혼합 대상: 강사 `MICROPHONE`, 학생별 익명 `MICROPHONE`, `SCREEN_SHARE_AUDIO`.
- 각 트랙 `adelay`로 `offset_ms` 정렬 → `SCREEN_SHARE_AUDIO`는 음량을 낮춰 발언을 덮지 않게 함 → `amix` → `alimiter`로 clipping 방지.
- 학생 마이크 등 선택 오디오 일부 누락은 경고 후 정상 진행. `SCREEN_SHARE_AUDIO` 없어도 실패로 처리하지 않는다.

## 6. 출력·검증

- 기본 출력: `1280x720`, `30fps`, H.264(video) + AAC(audio) MP4.
- 전체 길이 = `max(offset_ms + duration_ms)`.
- Track Egress webm/ogg는 타임스탬프가 불연속일 수 있어, 각 트랙을 raw(yuv420p / s16le PCM)로 디코드한 뒤 재조립한다(smoke 검증 기법).
- 오디오 혼합은 `amix ... normalize=0`을 사용한다. 입력 수가 많을 때 기본 정규화가 음량을 낮추는 문제(phase-5-report 기록)를 막는다.
- `lecture.mp4.partial`로 먼저 생성 → 검증 성공 시에만 `lecture.mp4`로 atomic rename.
- 검증 항목(phase-4/5 검증 방식과 동일):
  - `ffprobe`로 codec(H.264/AAC)·해상도(1280x720)·duration(예상과 오차 내)
  - **전체 파일 decode 무오류**(`ffmpeg -v error -i out -f null -`) = "재생 가능" 판정
  - SHA-256 산출·기록
  - 레이아웃 육안 확인용 **대표 프레임 PNG**를 `<output_dir>/frames/`에 추출(QA 보조, best-effort)

## 7. 운영 안정성

- **중복 실행 방지**: 세션별 `flock`(비차단). 이미 처리 중이면 종료 코드로 알리고 중복 병합하지 않는다.
- **원본 보존**: 최종 검증 성공 전에는 원본 트랙·manifest를 삭제하지 않는다(이 Worker는 어떤 경우에도 원본을 삭제하지 않는다).
- **재시도**: 실패 시 재실행하면 `.partial`을 새로 만들고 처음부터 다시 시도한다(부분 산출물에 의존하지 않는다). MySQL lease·job 상태머신은 68 범위.

## 8. 종료 코드

| 코드 | 의미 |
| --- | --- |
| `0` | 최종 MP4 생성·검증 성공 |
| `2` | manifest 오류(스키마·enum·경로 탈출·화면공유 구간 겹침) |
| `3` | 필수 영상 없음(강사 카메라·화면공유가 전혀 없음) |
| `4` | 입력 트랙 검증 실패(sha256 불일치·ffprobe 실패) |
| `5` | FFmpeg 병합 실패 |
| `6` | 최종 MP4 검증 실패 |
| `9` | 락 획득 실패(이미 처리 중) |

선택 트랙(학생 마이크·화면 오디오) 일부 누락은 경고를 남기고 `0`으로 정상 종료할 수 있다.
