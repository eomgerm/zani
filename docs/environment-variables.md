# 환경변수

`필수` 없으면 서버가 뜨지 않음 · `배포 필수` 뜨지만 기능이 동작하지 않음 · `선택` 비우면 기본값.
`(공통)` 은 프로필이 기본값을 덮지 않는다는 뜻이다.

## 인증·CORS

| 환경변수 | 기본값 | dev | prod | 필수 여부 |
| --- | --- | --- | --- | --- |
| `GOOGLE_OAUTH_CLIENT_ID` | — | (공통) | (공통) | 필수 |
| `JWT_SECRET` | — | — | — | 필수 (dev·prod 전용) |
| `LOCAL_JWT_SECRET` | `zani-local-jwt-secret-key-must-be-at-least-thirty-two-bytes` | — | — | 선택 (local 전용) |
| `FRONTEND_ORIGIN` | — | — | — | 필수 (dev·prod 전용) |
| `LOCAL_FRONTEND_ORIGIN` | `http://localhost:3000` | — | — | 선택 (local 전용) |
| `AUTH_COOKIE_SAME_SITE` | — | `None` | `Lax` | 선택 |

## 데이터베이스·Redis

| 환경변수 | 기본값 | dev | prod | 필수 여부 |
| --- | --- | --- | --- | --- |
| `DB_URL` | — | — | — | 필수 (dev·prod 전용) |
| `DB_USERNAME` | — | — | — | 필수 (dev·prod 전용) |
| `DB_PASSWORD` | — | — | — | 필수 (dev·prod 전용) |
| `REDIS_HOST` | — | — | — | 필수 (dev·prod 전용) |
| `REDIS_PORT` | — | — | — | 필수 (dev·prod 전용) |
| `REDIS_PASSWORD` | — | 빈 값 | 빈 값 | 선택 |
| `REDIS_SSL_ENABLED` | — | `false` | `true` | 선택 |
| `LOCAL_DB_URL` | `jdbc:mysql://localhost:3306/zani` | — | — | 선택 (local 전용) |
| `LOCAL_DB_USERNAME` | `root` | — | — | 선택 (local 전용) |
| `LOCAL_DB_PASSWORD` | `root` | — | — | 선택 (local 전용) |
| `LOCAL_REDIS_HOST` | `localhost` | — | — | 선택 (local 전용) |
| `LOCAL_REDIS_PORT` | `6379` | — | — | 선택 (local 전용) |
| `LOCAL_REDIS_PASSWORD` | 빈 값 | — | — | 선택 (local 전용) |

## 세션

| 환경변수 | 기본값 | dev | prod | 필수 여부 |
| --- | --- | --- | --- | --- |
| `SESSION_EXPIRY_SWEEP_DELAY` | `PT1M` | (공통) | (공통) | 선택 |

## 참여도 타임라인 (리포트)

| 환경변수 | 기본값 | dev | prod | 필수 여부 |
| --- | --- | --- | --- | --- |
| `ATTENTION_TIMELINE_MINIMUM_ELIGIBLE` | `5` | (공통) | (공통) | 선택 (2 미만 금지) |
| `ATTENTION_TIMELINE_MAX_DURATION` | `PT3H` | (공통) | (공통) | 선택 |
| `ATTENTION_TIMELINE_SAMPLING_INTERVAL` | `PT5S` | (공통) | (공통) | 선택 |
| `ATTENTION_TIMELINE_GROUP_WINDOW` | `PT5M` | (공통) | (공통) | 선택 |
| `ATTENTION_TIMELINE_FOCUS_BUCKET` | `PT30S` | (공통) | (공통) | 선택 |
| `ATTENTION_TIMELINE_FOCUS_COVERAGE_FLOOR` | `0.7` | (공통) | (공통) | 선택 |
| `ATTENTION_TIMELINE_CONNECTION_GAP` | `PT30S` | (공통) | (공통) | 선택 |
| `ATTENTION_TIMELINE_REQUIRED_CONNECTION` | `PT1M` | (공통) | (공통) | 선택 |
| `ATTENTION_TIMELINE_MEASUREMENT_OUTAGE` | `PT1M` | (공통) | (공통) | 선택 |
| `ATTENTION_TIMELINE_SIGNIFICANT_TTL` | `PT5M` | (공통) | (공통) | 선택 |
| `ATTENTION_TIMELINE_UNMEASURABLE_RUN_LENGTH` | `3` | (공통) | (공통) | 선택 |
| `ATTENTION_TIMELINE_DISTRACTION_START_RATIO` | `0.30` | (공통) | (공통) | 선택 |
| `ATTENTION_TIMELINE_DISTRACTION_START_HOLD` | `PT20S` | (공통) | (공통) | 선택 |
| `ATTENTION_TIMELINE_DISTRACTION_END_RATIO` | `0.20` | (공통) | (공통) | 선택 |
| `ATTENTION_TIMELINE_DISTRACTION_END_HOLD` | `PT30S` | (공통) | (공통) | 선택 |
| `ATTENTION_TIMELINE_DISTRACTION_MERGE_GAP` | `PT15S` | (공통) | (공통) | 선택 |

## 실시간 코칭

| 환경변수 | 기본값 | dev | prod | 필수 여부 |
| --- | --- | --- | --- | --- |
| `COACHING_TRIGGER_THRESHOLD` | `0.30` | (공통) | (공통) | 선택 |
| `COACHING_TRIGGER_COOLDOWN` | `PT10M` | (공통) | (공통) | 선택 |
| `COACHING_TRIGGER_MINIMUM_AUDIO` | `PT1M` | (공통) | (공통) | 선택 |
| `COACH_HISTORY_RETRY_DELAY` | `PT5S` | (공통) | (공통) | 선택 |
| `COACH_TIP_MIN_CONFIDENCE` | `0.5` | (공통) | (공통) | 선택 |
| `COACH_TIP_MAX_COMPLETION_TOKENS` | `100` | (공통) | (공통) | 선택 |
| `COACH_TIP_TRANSCRIPT_TAIL_CHARS` | `3000` | (공통) | (공통) | 선택 |
| `COACH_PIPELINE_THREAD_COUNT` | `2` | (공통) | (공통) | 선택 |
| `COACH_PIPELINE_QUEUE_CAPACITY` | `6` | (공통) | (공통) | 선택 |
| `COACH_PIPELINE_MAX_TRIGGER_DELAY` | `PT10S` | (공통) | (공통) | 선택 |

## GMS (LLM · STT)

| 환경변수 | 기본값 | dev | prod | 필수 여부 |
| --- | --- | --- | --- | --- |
| `GMS_API_KEY` | 빈 값 | (공통) | (공통) | 배포 필수 |
| `GMS_MOCK_ENABLED` | `true` | (공통) | `false` | 선택 |
| `GMS_BASE_URL` | `https://gms.ssafy.io/gmsapi/api.openai.com` | (공통) | (공통) | 선택 |
| `GMS_READ_TIMEOUT` | `10s` | (공통) | (공통) | 선택 |
| `GMS_CONNECT_TIMEOUT` | `2s` | (공통) | (공통) | 선택 |
| `GMS_STT_MODEL` | `whisper-1` | (공통) | (공통) | 선택 |
| `GMS_TRANSCRIBE_TIMEOUT` | `20s` | (공통) | (공통) | 선택 |
| `GMS_TRANSCRIBE_LANGUAGE` | `ko` | (공통) | (공통) | 선택 |
| `GMS_POSTCLASS_TRANSCRIBE_TIMEOUT` | `180s` | (공통) | (공통) | 선택 |
| `GMS_TIP_MODEL` | `gpt-5.4-mini` | (공통) | (공통) | 선택 |
| `GMS_TIP_TIMEOUT` | `6s` | (공통) | (공통) | 선택 |
| `GMS_ANALYSIS_MODEL` | `gpt-5.4-mini` | (공통) | (공통) | 선택 |
| `GMS_ANALYSIS_TIMEOUT` | `60s` | (공통) | (공통) | 선택 |

## 사후 처리 — 전사

| 환경변수 | 기본값 | dev | prod | 필수 여부 |
| --- | --- | --- | --- | --- |
| `POSTCLASS_TRANSCRIPTION_ENABLED` | `true` | (공통) | (공통) | 선택 |
| `POSTCLASS_SOURCE_ROOT` | `/out` | (공통) | (공통) | 선택 |
| `POSTCLASS_WORK_DIR` | `/tmp/zani-postclass` | (공통) | (공통) | 선택 |
| `POSTCLASS_FFMPEG_PATH` | `ffmpeg` | (공통) | (공통) | 선택 |
| `POSTCLASS_FFPROBE_PATH` | `ffprobe` | (공통) | (공통) | 선택 |
| `POSTCLASS_FFMPEG_TIMEOUT` | `PT60S` | (공통) | (공통) | 선택 |
| `POSTCLASS_TRANSCRIPTION_POLL_DELAY` | `PT10S` | (공통) | (공통) | 선택 |
| `POSTCLASS_CHUNK_DURATION` | `PT10M` | (공통) | (공통) | 선택 |
| `POSTCLASS_DISPATCH_BATCH_SIZE` | `5` | (공통) | (공통) | 선택 |
| `POSTCLASS_TRANSCRIPTION_LEASE_DURATION` | `PT5M` | (공통) | (공통) | 선택 |
| `POSTCLASS_MAX_UPLOAD_BYTES` | `25165824` (24 MiB) | (공통) | (공통) | 선택 |
| `POSTCLASS_TRANSCRIPTION_CONCURRENCY` | `2` | (공통) | (공통) | 선택 |
| `POSTCLASS_SILENCE_PREFILTER_ENABLED` | `false` | (공통) | (공통) | 선택 |

## 사후 처리 — 분석

| 환경변수 | 기본값 | dev | prod | 필수 여부 |
| --- | --- | --- | --- | --- |
| `POSTCLASS_ANALYSIS_ENABLED` | `true` | (공통) | (공통) | 선택 |
| `POSTCLASS_ANALYSIS_POLL_DELAY` | `PT10S` | (공통) | (공통) | 선택 |
| `POSTCLASS_ANALYSIS_DISPATCH_BATCH_SIZE` | `5` | (공통) | (공통) | 선택 |
| `POSTCLASS_ANALYSIS_LEASE_DURATION` | `PT60M` | (공통) | (공통) | 선택 |
| `POSTCLASS_ANALYSIS_MAX_COMPLETION_TOKENS` | `3000` | (공통) | (공통) | 선택 |
| `POSTCLASS_CONTENT_ANALYSIS_MAX_COMPLETION_TOKENS` | `12000` | (공통) | (공통) | 선택 |
| `POSTCLASS_INSTRUCTOR_ANALYSIS_MAX_COMPLETION_TOKENS` | `3000` | (공통) | (공통) | 선택 |
| `POSTCLASS_NOTE_INACTIVITY_SWEEP_DELAY` | `PT1M` | (공통) | (공통) | 선택 |

## 알림 메일

| 환경변수 | 기본값 | dev | prod | 필수 여부 |
| --- | --- | --- | --- | --- |
| `NOTIFICATION_EMAIL_ENABLED` | `false` | (공통) | (공통) | 선택 |
| `NOTIFICATION_EMAIL_FROM` | `no-reply@zani.app` | (공통) | (공통) | 배포 필수 |
| `NOTIFICATION_EMAIL_RELAY_DELAY` | `PT10S` | (공통) | (공통) | 선택 |
| `NOTIFICATION_APP_BASE_URL` | `https://zani.app` | (공통) | (공통) | 배포 필수 |
| `SPRING_MAIL_HOST` | — | — | — | 배포 필수 (메일 켤 때) |
| `SPRING_MAIL_PORT` | — | — | — | 배포 필수 (메일 켤 때) |
| `SPRING_MAIL_USERNAME` | — | — | — | 배포 필수 (메일 켤 때) |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH` | — | — | — | 배포 필수 (메일 켤 때) |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` | — | — | — | 배포 필수 (메일 켤 때) |

## LiveKit

| 환경변수 | 기본값 | dev | prod | 필수 여부 |
| --- | --- | --- | --- | --- |
| `LIVEKIT_URL` | 빈 값 | (공통) | (공통) | 배포 필수 |
| `LIVEKIT_API_KEY` | 빈 값 | (공통) | (공통) | 배포 필수 |
| `LIVEKIT_API_SECRET` | 빈 값 | (공통) | (공통) | 배포 필수 |
| `LIVEKIT_ENVIRONMENT` | `local` | (공통) | (공통) | 배포 필수 |
| `LIVEKIT_TOKEN_TTL` | `PT10M` | (공통) | (공통) | 선택 |

## 오디오 클립 (코칭 입력)

| 환경변수 | 기본값 | dev | prod | 필수 여부 |
| --- | --- | --- | --- | --- |
| `AUDIO_CLIP_WINDOW` | `PT5M` | (공통) | (공통) | 선택 |
| `AUDIO_CLIP_MIN_TRANSCRIBABLE` | `PT1M` | (공통) | (공통) | 선택 |
| `AUDIO_CLIP_SAMPLE_RATE` | `48000` | (공통) | (공통) | 선택 |
| `AUDIO_CLIP_MAX_SESSIONS` | `8` | (공통) | (공통) | 선택 |
| `AUDIO_CLIP_STREAM_URL_TEMPLATE` | `ws://127.0.0.1:18080/internal/audio/{sessionId}` | (공통) | (공통) | 선택 |

## 녹화

| 환경변수 | 기본값 | dev | prod | 필수 여부 |
| --- | --- | --- | --- | --- |
| `RECORDING_BASE_PATH` | `/srv/zani/recordings` | (공통) | (공통) | 선택 |
| `RECORDING_MEDIA_ROOT` | `/srv/zani/recordings` | (공통) | (공통) | 선택 |
| `RECORDING_MEDIA_URL_TTL` | `PT5M` | (공통) | (공통) | 선택 |
| `RECORDING_MEDIA_URL_TEMPLATE` | `http://localhost:18080/api/v1/sessions/{sessionId}/media` | (공통) | (공통) | 배포 필수 (기본값이 로컬 주소) |
| `RECORDING_OUTBOX_RELAY_ENABLED` | `true` | (공통) | (공통) | 선택 |
| `RECORDING_OUTBOX_RELAY_DELAY` | `PT5S` | (공통) | (공통) | 선택 |
| `RECORDING_FINALIZATION_ENABLED` | `true` | (공통) | (공통) | 선택 |
| `RECORDING_FINALIZATION_SOURCE_ROOT` | `/out` | (공통) | (공통) | 선택 |
| `RECORDING_FINALIZATION_OUTPUT_ROOT` | `/finalized` | (공통) | (공통) | 선택 |
| `RECORDING_FINALIZATION_WORKER_PATH` | `/app/media/finalize-recording.sh` | (공통) | (공통) | 선택 |
| `RECORDING_FINALIZATION_PROCESS_TIMEOUT` | `PT4H` | (공통) | (공통) | 선택 |
| `RECORDING_FINALIZATION_POLL_DELAY` | `PT15S` | (공통) | (공통) | 선택 |
| `RECORDING_FINALIZATION_BATCH_SIZE` | `5` | (공통) | (공통) | 선택 |
| `RECORDING_FINALIZATION_LEASE_DURATION` | `PT5H` | (공통) | (공통) | 선택 |
| `RECORDING_FINALIZATION_WAITING_DELAY` | `PT15S` | (공통) | (공통) | 선택 |
| `RECORDING_FINALIZATION_RETRY_DELAY` | `PT1M` | (공통) | (공통) | 선택 |
| `RECORDING_FINALIZATION_MAX_ATTEMPTS` | `3` | (공통) | (공통) | 선택 |

## 검출기 계약

| 환경변수 | 기본값 | dev | prod | 필수 여부 |
| --- | --- | --- | --- | --- |
| `ATTENTION_DETECTOR_SUPPORTED_FEATURE_SCHEMA_VERSIONS_0` | `mediapipe_98_v1` | (공통) | (공통) | 선택 (리스트라 `_0` 인덱스 필요) |

## 프론트엔드 (빌드 시점에 굳는다)

| 환경변수 | 기본값 | dev | prod | 필수 여부 |
| --- | --- | --- | --- | --- |
| `NEXT_PUBLIC_API_BASE_URL` | 빈 값 (같은 도메인 `/api`) | (공통) | (공통) | 선택 |
| `NEXT_PUBLIC_GOOGLE_CLIENT_ID` | 빈 값 | (공통) | (공통) | 배포 필수 (`GOOGLE_OAUTH_CLIENT_ID` 에서 주입) |
| `NEXT_PUBLIC_ATTENTION_ASSET_BASE` | `/attention` | (공통) | (공통) | 선택 |
| `FRONTEND_IMAGE` | `zani/frontend:dev` | (공통) | (공통) | 선택 |

## AI 모듈

환경변수 없음 (설정은 모두 CLI 인자).
