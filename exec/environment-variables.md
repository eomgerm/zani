# ZANI 환경변수 및 자격증명 명세

> 기준: `dev` SHA `c4c1223a9574455e45a4c43af6bb719eccf4f45b`

이 문서는 제출·인수인계용 환경변수 정본이다. 실제 비밀번호, API Key, 토큰, 개인 계정 정보는 기록하지 않는다.

## 1. 표기와 설정 위치

- **필수**: 없으면 애플리케이션이 정상 기동하지 않는다.
- **배포 필수**: 서버는 기동할 수 있지만 해당 기능이 동작하지 않는다.
- **선택**: 생략하면 코드 또는 Compose 기본값을 사용한다.
- `<...>`: 배포자가 발급하거나 생성해야 하는 값이며 실제 값은 제출물에 넣지 않는다.

| 위치 | 역할 | 제출 여부 |
| --- | --- | --- |
| `infrastructure/application/runtime.env.example` | 안전한 운영 예시 | 포함 |
| `/etc/zani/application/runtime.env` | 실제 운영 비밀이 아닌 설정 | 제외 |
| `/etc/zani/application/secrets/*` | DB·Redis·JWT·LiveKit·GMS·SMTP secret | 제외 |
| `infrastructure/application/compose.yaml` | 환경변수·secret·볼륨 전달 | 포함 |
| `backend/docker-entrypoint.sh` | Docker secret을 Spring 환경변수로 export | 포함 |

환경변수를 변경하면 단순 `docker restart`가 아니라 배포 Wrapper 또는 `docker compose up -d --force-recreate`로 컨테이너를 재생성한다. Frontend의 `NEXT_PUBLIC_*`는 이미지 빌드 시 굳으므로 Frontend 이미지를 다시 빌드해야 한다.

## 2. Docker secret

| secret 파일명 | 컨테이너 환경변수 | 필수 조건 |
| --- | --- | --- |
| `mysql_app_password` | `DB_PASSWORD` | 항상 필수 |
| `redis_app_password` | `REDIS_PASSWORD` | 항상 필수 |
| `jwt_secret` | `JWT_SECRET` | 항상 필수, 32바이트 이상 |
| `livekit_api_key` | `LIVEKIT_API_KEY` | 운영 필수 |
| `livekit_api_secret` | `LIVEKIT_API_SECRET` | 운영 필수 |
| `gms_api_key` | `GMS_API_KEY` | 항상 필수, 빈 값이면 기동 실패 |
| `smtp_password` | `SPRING_MAIL_PASSWORD` | `NOTIFICATION_EMAIL_ENABLED=true`일 때 필수 |

호스트 기본 위치는 `/etc/zani/application/secrets/<파일명>`이고 컨테이너에서는 `/run/secrets/<파일명>`으로 읽는다. GMS·SMTP secret은 각각 `install-gms-api-key.sh`, `install-smtp-password.sh`로 설치한다.

`SMTP_PASSWORD_FILE`은 Spring 환경변수가 아니라 Compose가 호스트의 SMTP secret 파일을 선택하는 배포 변수다. 생략하면 `/etc/zani/application/secrets/smtp_password`를 사용한다.

## 3. 인증·CORS

| 환경변수 | 기본값·운영값 | 필수 여부 | 용도 |
| --- | --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | 운영 `dev` | 필수 | 배포 프로필 |
| `GOOGLE_OAUTH_CLIENT_ID` | `<GOOGLE_WEB_CLIENT_ID>` | 필수 | Google ID Token audience |
| `JWT_SECRET` | Docker secret | 필수 | JWT 서명 키 |
| `FRONTEND_ORIGIN` | `https://i15a105.p.ssafy.io` | 필수 | CORS 허용 origin |
| `AUTH_COOKIE_SAME_SITE` | dev `None`, prod `Lax` | 선택 | 인증 쿠키 정책 |
| `LOCAL_JWT_SECRET` | 로컬 개발용 32바이트 이상 문자열 | local 선택 | local JWT 키 |
| `LOCAL_FRONTEND_ORIGIN` | `http://localhost:3000` | local 선택 | local CORS origin |

## 4. 데이터베이스·Redis

| 환경변수 | 운영값·예시 | 필수 여부 | 용도 |
| --- | --- | --- | --- |
| `DB_URL` | `jdbc:mysql://mysql:3306/zani?...` | 필수 | MySQL JDBC 주소 |
| `DB_USERNAME` | `zani` | 필수 | 애플리케이션 DB 계정 |
| `DB_PASSWORD` | Docker secret | 필수 | DB 비밀번호 |
| `REDIS_HOST` | `redis-app` | 필수 | Application Redis 호스트 |
| `REDIS_PORT` | `6379` | 필수 | Redis 포트 |
| `REDIS_PASSWORD` | Docker secret | 필수 | Redis 인증 |
| `REDIS_SSL_ENABLED` | 운영 Compose `false` | 선택 | 내부 Docker network TLS 여부 |
| `LOCAL_DB_URL` | `jdbc:mysql://localhost:3306/zani` | local 선택 | 로컬 DB 주소 |
| `LOCAL_DB_USERNAME` | `root` | local 선택 | 로컬 DB 계정 |
| `LOCAL_DB_PASSWORD` | `root` | local 선택 | 로컬 DB 비밀번호 |
| `LOCAL_REDIS_HOST` | `localhost` | local 선택 | 로컬 Redis 호스트 |
| `LOCAL_REDIS_PORT` | `6379` | local 선택 | 로컬 Redis 포트 |
| `LOCAL_REDIS_PASSWORD` | 빈 값 | local 선택 | 로컬 Redis 비밀번호 |

## 5. GMS(LLM·STT)

| 환경변수 | 기본값·운영값 | 필수 여부 | 용도 |
| --- | --- | --- | --- |
| `GMS_API_KEY` | Docker secret | 배포 필수 | SSAFY GMS 인증 |
| `GMS_MOCK_ENABLED` | 코드 `true`, 운영 `false` | 선택 | mock 사용 여부 |
| `GMS_BASE_URL` | `https://gms.ssafy.io/gmsapi/api.openai.com` | 선택 | GMS API 주소 |
| `GMS_READ_TIMEOUT` | `10s` | 선택 | 일반 읽기 timeout |
| `GMS_CONNECT_TIMEOUT` | `2s` | 선택 | 연결 timeout |
| `GMS_STT_MODEL` | `whisper-1` | 선택 | STT 모델 |
| `GMS_TRANSCRIBE_TIMEOUT` | `20s` | 선택 | 실시간 STT timeout |
| `GMS_TRANSCRIBE_LANGUAGE` | `ko` | 선택 | STT 언어 |
| `GMS_POSTCLASS_TRANSCRIBE_TIMEOUT` | `180s` | 선택 | 사후 STT timeout |
| `GMS_TIP_MODEL` | `gpt-5.4-mini` | 선택 | 코칭 팁 모델 |
| `GMS_TIP_TIMEOUT` | `6s` | 선택 | 코칭 팁 timeout |
| `GMS_ANALYSIS_MODEL` | `gpt-5.4-mini` | 선택 | 사후 분석 모델 |
| `GMS_ANALYSIS_TIMEOUT` | `60s` | 선택 | 사후 분석 timeout |
| `GMS_ASSISTANT_TIMEOUT` | `25s` | 선택 | 리포트 챗봇 timeout |
| `REPORT_ASSISTANT_MAX_COMPLETION_TOKENS` | `1500` | 선택 | 리포트 챗봇 최대 출력 토큰 |

## 6. 세션·참여도·실시간 코칭

| 환경변수 | 기본값 | 용도 |
| --- | --- | --- |
| `SESSION_EXPIRY_SWEEP_DELAY` | `PT1M` | 만료 세션 정리 주기 |
| `ATTENTION_TIMELINE_MINIMUM_ELIGIBLE` | `5` | 타임라인 최소 유효 인원(2 미만 금지) |
| `ATTENTION_TIMELINE_MAX_DURATION` | `PT3H` | 최대 수업 길이 |
| `ATTENTION_TIMELINE_SAMPLING_INTERVAL` | `PT5S` | 샘플 간격 |
| `ATTENTION_TIMELINE_GROUP_WINDOW` | `PT5M` | 그룹 집계 창 |
| `ATTENTION_TIMELINE_FOCUS_BUCKET` | `PT30S` | 집중도 bucket |
| `ATTENTION_TIMELINE_FOCUS_COVERAGE_FLOOR` | `0.7` | 최소 데이터 coverage |
| `ATTENTION_TIMELINE_CONNECTION_GAP` | `PT30S` | 연결 공백 허용값 |
| `ATTENTION_TIMELINE_REQUIRED_CONNECTION` | `PT1M` | 최소 연결 시간 |
| `ATTENTION_TIMELINE_MEASUREMENT_OUTAGE` | `PT1M` | 측정 중단 판정 |
| `ATTENTION_TIMELINE_SIGNIFICANT_TTL` | `PT5M` | 유의 상태 TTL |
| `ATTENTION_TIMELINE_UNMEASURABLE_RUN_LENGTH` | `3` | 연속 측정불가 판정 수 |
| `ATTENTION_TIMELINE_DISTRACTION_START_RATIO` | `0.30` | 산만 시작 비율 |
| `ATTENTION_TIMELINE_DISTRACTION_START_HOLD` | `PT20S` | 산만 시작 유지시간 |
| `ATTENTION_TIMELINE_DISTRACTION_END_RATIO` | `0.20` | 산만 종료 비율 |
| `ATTENTION_TIMELINE_DISTRACTION_END_HOLD` | `PT30S` | 산만 종료 유지시간 |
| `ATTENTION_TIMELINE_DISTRACTION_MERGE_GAP` | `PT15S` | 구간 병합 간격 |
| `COACHING_TRIGGER_THRESHOLD` | `0.30` | 코칭 트리거 비율 |
| `COACHING_TRIGGER_COOLDOWN` | `PT10M` | 코칭 쿨타임 |
| `COACHING_TRIGGER_MINIMUM_AUDIO` | `PT1M` | 최소 오디오 길이 |
| `COACH_HISTORY_RETRY_DELAY` | `PT5S` | 결과 기록 재시도 |
| `COACH_TIP_MIN_CONFIDENCE` | `0.5` | 개념 confidence 하한 |
| `COACH_TIP_MAX_COMPLETION_TOKENS` | `100` | 팁 추출 출력 상한 |
| `COACH_TIP_TRANSCRIPT_TAIL_CHARS` | `3000` | 전사 입력 꼬리 길이 |
| `COACH_PIPELINE_THREAD_COUNT` | `2` | 전용 worker 수 |
| `COACH_PIPELINE_QUEUE_CAPACITY` | `6` | 큐 상한 |
| `COACH_PIPELINE_MAX_TRIGGER_DELAY` | `PT10S` | 처리 시작 허용 지연 |

위 항목은 모두 선택값이며 생략하면 기본값을 사용한다.

## 7. 사후 처리 — 전사·분석

| 환경변수 | 기본값·운영값 | 용도 |
| --- | --- | --- |
| `POSTCLASS_TRANSCRIPTION_ENABLED` | `true` | 전사 스케줄러 활성화 |
| `POSTCLASS_SOURCE_ROOT` | `/out` | Track Egress 원본 읽기 경로 |
| `POSTCLASS_WORK_DIR` | `/tmp/zani-postclass` | 임시 작업 경로 |
| `POSTCLASS_FFMPEG_PATH` | `ffmpeg` | FFmpeg 실행 파일 |
| `POSTCLASS_FFPROBE_PATH` | `ffprobe` | FFprobe 실행 파일 |
| `POSTCLASS_FFMPEG_TIMEOUT` | `PT60S` | 분할 timeout |
| `POSTCLASS_TRANSCRIPTION_POLL_DELAY` | `PT10S` | 작업 조회 주기 |
| `POSTCLASS_CHUNK_DURATION` | `PT10M` | GMS 전사 청크 길이 |
| `POSTCLASS_DISPATCH_BATCH_SIZE` | `5` | 조회 batch 수 |
| `POSTCLASS_TRANSCRIPTION_LEASE_DURATION` | `PT5M` | 청크 lease |
| `POSTCLASS_MAX_UPLOAD_BYTES` | `25165824` | 업로드 상한(24 MiB) |
| `POSTCLASS_TRANSCRIPTION_CONCURRENCY` | `2` | GMS 동시 호출 수 |
| `POSTCLASS_SILENCE_PREFILTER_ENABLED` | `false` | 호출 전 무음 선별(현재 미사용) |
| `POSTCLASS_TRANSCRIPTION_HALLUCINATION_FILTER_ENABLED` | `true` | `no_speech_prob` 환각 필터 |
| `POSTCLASS_TRANSCRIPTION_NO_SPEECH_THRESHOLD` | `0.98` | 무음 확률 임곗값 |
| `POSTCLASS_TRANSCRIPTION_REPEATED_PHRASE_FILTER_ENABLED` | `true` | 반복 문구 환각 필터 |
| `POSTCLASS_ANALYSIS_ENABLED` | `true` | 사후 분석 활성화 |
| `POSTCLASS_ANALYSIS_POLL_DELAY` | `PT10S` | 분석 조회 주기 |
| `POSTCLASS_ANALYSIS_DISPATCH_BATCH_SIZE` | `5` | 분석 batch 수 |
| `POSTCLASS_ANALYSIS_LEASE_DURATION` | `PT60M` | 분석 lease |
| `POSTCLASS_ANALYSIS_MAX_COMPLETION_TOKENS` | `3000` | 학생 분석 출력 상한 |
| `POSTCLASS_CONTENT_ANALYSIS_MAX_COMPLETION_TOKENS` | `12000` | 공통 내용 분석 출력 상한 |
| `POSTCLASS_INSTRUCTOR_ANALYSIS_MAX_COMPLETION_TOKENS` | `3000` | 강사 분석 출력 상한 |
| `POSTCLASS_NOTE_INACTIVITY_SWEEP_DELAY` | `PT1M` | 메모 비활성 확정 주기 |

모두 선택값이다. 환각 필터를 긴급 해제할 때는 `POSTCLASS_TRANSCRIPTION_HALLUCINATION_FILTER_ENABLED=false`로 바꾸고 Backend를 재배포한다. 원본 체크포인트는 변경되지 않는다.

## 8. 알림 이메일

| 환경변수 | 기본값·운영값 | 필수 여부 | 용도 |
| --- | --- | --- | --- |
| `NOTIFICATION_EMAIL_ENABLED` | 코드 `false`, 운영 `true` | 선택 | 이메일 스케줄러 활성화 |
| `NOTIFICATION_EMAIL_FROM` | `<발신 Gmail 주소>` | 기능 사용 시 필수 | 발신 주소 |
| `NOTIFICATION_EMAIL_RELAY_DELAY` | `PT10S` | 선택 | outbox 처리 주기 |
| `NOTIFICATION_APP_BASE_URL` | `https://i15a105.p.ssafy.io` | 배포 필수 | 이메일 링크 기준 URL |
| `SPRING_MAIL_HOST` | `smtp.gmail.com` | 기능 사용 시 필수 | SMTP 호스트 |
| `SPRING_MAIL_PORT` | `587` | 기능 사용 시 필수 | SMTP 포트 |
| `SPRING_MAIL_USERNAME` | `<발신 Gmail 주소>` | 기능 사용 시 필수 | SMTP 계정 |
| `SPRING_MAIL_PASSWORD` | Docker secret | 기능 사용 시 필수 | Gmail 앱 비밀번호 |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH` | `true` | 기능 사용 시 필수 | SMTP 인증 |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` | `true` | 기능 사용 시 필수 | STARTTLS |

## 9. LiveKit·오디오 클립

| 환경변수 | 기본값·운영값 | 필수 여부 | 용도 |
| --- | --- | --- | --- |
| `LIVEKIT_URL` | 운영 WSS 주소 | 배포 필수 | LiveKit WebSocket |
| `LIVEKIT_API_KEY` | Docker secret | 배포 필수 | LiveKit API Key |
| `LIVEKIT_API_SECRET` | Docker secret | 배포 필수 | LiveKit API Secret |
| `LIVEKIT_ENVIRONMENT` | 운영 환경 식별값 | 배포 필수 | room·Egress 환경 구분 |
| `LIVEKIT_TOKEN_TTL` | `PT10M` | 선택 | 토큰 유효시간 |
| `AUDIO_CLIP_WINDOW` | `PT5M` | 선택 | 실시간 코칭 오디오 창 |
| `AUDIO_CLIP_MIN_TRANSCRIBABLE` | `PT1M` | 선택 | 최소 전사 길이 |
| `AUDIO_CLIP_SAMPLE_RATE` | `48000` | 선택 | PCM 샘플레이트 |
| `AUDIO_CLIP_MAX_SESSIONS` | `8` | 선택 | 링버퍼 최대 세션 수 |
| `AUDIO_CLIP_STREAM_URL_TEMPLATE` | `ws://127.0.0.1:18080/internal/audio/{sessionId}` | 선택 | 내부 오디오 스트림 |

## 10. 녹화·최종 MP4·썸네일

| 환경변수 | 코드 기본값 | 운영 Compose 값 | 필수 여부 | 용도 |
| --- | --- | --- | --- | --- |
| `RECORDING_BASE_PATH` | `/srv/zani/recordings` | `/out` | 선택 | LiveKit Egress 출력 경로 |
| `RECORDING_MEDIA_ROOT` | `/srv/zani/recordings` | `/recordings` | 배포 필수 | Backend의 최종 MP4 읽기 경로 |
| `RECORDING_MEDIA_URL_TTL` | `PT5M` | 기본값 | 선택 | 서명 URL 유효시간 |
| `RECORDING_MEDIA_URL_TEMPLATE` | 로컬 API URL | `https://i15a105.p.ssafy.io/api/v1/sessions/{sessionId}/media` | 배포 필수 | 미디어 API 주소 |
| `RECORDING_THUMBNAIL_URL_TEMPLATE` | 운영 URL 필요 | `https://i15a105.p.ssafy.io/api/v1/sessions/{sessionId}/thumbnail` | 배포 필수 | 썸네일 API 주소 |
| `RECORDING_OUTBOX_RELAY_ENABLED` | `true` | 기본값 | 선택 | Egress outbox relay |
| `RECORDING_OUTBOX_RELAY_DELAY` | `PT5S` | 기본값 | 선택 | outbox 조회 주기 |
| `RECORDING_FINALIZATION_ENABLED` | `true` | 기본값 | 선택 | MP4 최종화 활성화 |
| `RECORDING_FINALIZATION_SOURCE_ROOT` | `/out` | `/out` | 선택 | 원본 읽기 경로 |
| `RECORDING_FINALIZATION_OUTPUT_ROOT` | `/finalized` | `/finalized` | 선택 | 최종 MP4 쓰기 경로 |
| `RECORDING_FINALIZATION_WORKER_PATH` | `/app/media/finalize-recording.sh` | 동일 | 선택 | 병합 worker |
| `RECORDING_FINALIZATION_PROCESS_TIMEOUT` | `PT4H` | 기본값 | 선택 | 병합 timeout |
| `RECORDING_FINALIZATION_POLL_DELAY` | `PT15S` | 기본값 | 선택 | 작업 조회 주기 |
| `RECORDING_FINALIZATION_BATCH_SIZE` | `5` | 기본값 | 선택 | 조회 batch 수 |
| `RECORDING_FINALIZATION_LEASE_DURATION` | `PT5H` | 기본값 | 선택 | 최종화 lease |
| `RECORDING_FINALIZATION_WAITING_DELAY` | `PT15S` | 기본값 | 선택 | 입력 대기 주기 |
| `RECORDING_FINALIZATION_RETRY_DELAY` | `PT1M` | 기본값 | 선택 | 재시도 간격 |
| `RECORDING_FINALIZATION_MAX_ATTEMPTS` | `3` | 기본값 | 선택 | 최대 시도 횟수 |

운영 볼륨은 `/srv/zani/recordings/track-egress:/out:ro`, `/srv/zani/recordings:/finalized:rw`, `/srv/zani/recordings:/recordings:ro`로 역할을 분리한다.

## 11. Frontend 빌드 환경변수

| 환경변수 | 기본값·운영값 | 필수 여부 | 용도 |
| --- | --- | --- | --- |
| `NEXT_PUBLIC_API_BASE_URL` | 빈 값이면 같은 도메인 `/api` | 선택 | 공개 API base URL |
| `NEXT_PUBLIC_GOOGLE_CLIENT_ID` | `GOOGLE_OAUTH_CLIENT_ID`와 동일 | 배포 필수 | 브라우저 Google 로그인 |
| `NEXT_PUBLIC_ATTENTION_ASSET_BASE` | `/attention` | 선택 | 참여도 모델 asset 경로 |
| `BACKEND_IMAGE` | `zani/backend:dev` 또는 Git SHA tag | 선택 | Backend 배포 이미지 |
| `FRONTEND_IMAGE` | `zani/frontend:dev` 또는 Git SHA tag | 선택 | 배포 이미지 |

## 12. 적용 전 확인

1. `runtime.env.example`을 복사해 EC2의 `/etc/zani/application/runtime.env`를 작성한다.
2. 실제 비밀값은 `/etc/zani/application/secrets/*`에 설치하고 root 소유·읽기 제한 권한을 적용한다.
3. `docker compose config`로 환경변수, secret, 볼륨을 확인한다.
4. 배포 Wrapper로 컨테이너를 재생성한다.
5. Backend health, GMS 실제 호출, LiveKit 입장, 이메일, MP4 Range 재생을 확인한다.
6. 제출 파일과 Git 이력에 평문 비밀값이 없는지 검색한다.
