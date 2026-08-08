# ZANI 외부 서비스 및 외부 프로젝트 설정

> 기준: `dev` SHA `1233f4f2198c2eb86727a0914d6e830a1dbd38be`

이 문서는 프로젝트 실행에 필요한 외부 계정·API·오픈소스 서버의 역할과 재설정 방법을 정리한다. 실제 키와 비밀번호는 포함하지 않는다.

## 1. 한눈에 보기

| 구분 | 서비스·프로젝트 | 사용 목적 | 가입/자격증명 필요 |
| --- | --- | --- | --- |
| 런타임 외부 서비스 | Google Identity Services | Google 로그인 ID Token 발급 | Google Cloud 프로젝트, Web Client ID |
| 런타임 외부 서비스 | SSAFY GMS | 실시간/사후 STT, 코칭 팁, 리포트 분석 | SSAFY GMS API key |
| 런타임 외부 서비스 | Gmail SMTP | 리포트 완료 이메일 | Google 계정, 2단계 인증, 앱 비밀번호 |
| 런타임 오픈소스 | LiveKit Server | WebRTC SFU와 Room/Token 연동 | 자체 호스팅, API key/secret 자체 생성 |
| 런타임 오픈소스 | LiveKit Egress | 트랙별 녹화 | LiveKit과 동일 API key/secret |
| 런타임 오픈소스 | Coturn | 제한 네트워크 TURN/TLS relay | 자체 호스팅, shared secret 자체 생성 |
| 개발·협업 | GitLab | 소스·MR·Webhook | SSAFY GitLab 계정/토큰 |
| 개발·협업 | Jira | 이슈 관리 | SSAFY Atlassian 계정 |
| 개발·협업 | Jenkins | 빌드·배포·MR 리뷰 | 자체 호스팅 관리자 계정 |
| 개발·디자인 | Figma·Notion | 화면 설계·회의/명세 | 팀 워크스페이스 권한 |
| 인증서 | Let's Encrypt | Nginx와 TURN/TLS 인증서 | 도메인 DNS와 Certbot |

## 2. Google Identity Services

### 2.1 사용 위치

- Frontend가 `https://accounts.google.com/gsi/client`를 로드한다.
- 브라우저가 받은 Google ID Token을 `POST /api/v1/auth/login/google`로 전달한다.
- Backend는 같은 Web Client ID를 audience로 사용해 ID Token을 검증한다.

### 2.2 발급

1. Google Cloud Console에서 프로젝트를 만든다.
2. OAuth 동의 화면을 구성한다.
3. OAuth Client 유형을 **Web application**으로 생성한다.
4. Authorized JavaScript origin에 다음을 등록한다.
   - 운영: `https://i15a105.p.ssafy.io`
   - 로컬 개발이 필요하면 `http://localhost:3000`
5. 발급된 `*.apps.googleusercontent.com` 형식의 Client ID를 보관한다.

이 로그인 방식은 Google Identity Services의 callback으로 ID Token을 받으므로 별도 Client Secret을 Frontend에 넣지 않는다.

### 2.3 적용

EC2 `/etc/zani/application/runtime.env`:

```dotenv
GOOGLE_OAUTH_CLIENT_ID=<Google Web Client ID>
```

한 값을 Backend의 `GOOGLE_OAUTH_CLIENT_ID`와 Frontend build argument `NEXT_PUBLIC_GOOGLE_CLIENT_ID`에 같이 사용한다. Client ID는 공개 식별자이지만 운영 환경 값은 서버 설정 한 곳에서 관리한다.

검증:

```bash
sudo bash -c '
set -a
source /etc/zani/application/runtime.env
set +a
case "$GOOGLE_OAUTH_CLIENT_ID" in
  *.apps.googleusercontent.com) echo "Google OAuth Client ID 형식 정상" ;;
  *) echo "Google OAuth Client ID 확인 필요"; exit 1 ;;
esac
'
```

## 3. SSAFY GMS

### 3.1 사용 목적

| 기능 | 기본 모델 |
| --- | --- |
| 실시간 강사 오디오 전사 | `whisper-1` |
| 강의 종료 후 전체 전사 | `whisper-1` |
| 실시간 코칭 팁 | `gpt-5.4-mini` |
| 내용 구간·요약 분석 | `gpt-5.4-mini` |
| 학생별 참여 요약·복습·퀴즈 | `gpt-5.4-mini` |
| 강사 인사이트·피드백 | `gpt-5.4-mini` |

기본 Base URL:

```text
https://gms.ssafy.io/gmsapi/api.openai.com
```

ZANI는 OpenAI API를 직접 계약하지 않고 SSAFY GMS를 경유한다.

### 3.2 자격증명 설치

SSAFY GMS에서 발급한 API key를 대화형 설치 스크립트로 입력한다.

```bash
cd <검증된 저장소 checkout>
bash infrastructure/application/install-gms-api-key.sh
```

저장 위치:

```text
/etc/zani/application/secrets/gms_api_key
```

배포 환경에서는 반드시 다음 값을 사용한다.

```dotenv
GMS_MOCK_ENABLED=false
```

API key는 MR, Jira, Notion, Jenkins 로그에 적지 않는다.

### 3.3 주요 기본 설정

| 환경 변수 | 기본값 | 의미 |
| --- | --- | --- |
| `GMS_STT_MODEL` | `whisper-1` | STT 모델 |
| `GMS_TIP_MODEL` | `gpt-5.4-mini` | 코칭 팁 모델 |
| `GMS_ANALYSIS_MODEL` | `gpt-5.4-mini` | 사후 분석 모델 |
| `GMS_TRANSCRIBE_TIMEOUT` | `20s` | 실시간 STT timeout |
| `GMS_POSTCLASS_TRANSCRIBE_TIMEOUT` | `180s` | 사후 STT timeout |
| `GMS_TIP_TIMEOUT` | `6s` | 실시간 팁 timeout |
| `GMS_ANALYSIS_TIMEOUT` | `60s` | 사후 분석 timeout |
| `GMS_ASSISTANT_TIMEOUT` | `25s` | 리포트 챗봇 응답 timeout |
| `REPORT_ASSISTANT_MAX_COMPLETION_TOKENS` | `1500` | 리포트 챗봇 최대 출력 토큰 |

리포트 챗봇은 현재 기준 `dev`에 포함돼 있다. 두 값은 생략하면 위 기본값을 사용한다.

## 4. Gmail SMTP

### 4.1 사용 목적

사후 파이프라인이 리포트를 공개하면 이메일 수신을 허용한 학생에게 리포트 링크를 발송한다.

### 4.2 준비

1. 발신용 Google 계정에서 2단계 인증을 활성화한다.
2. Google 계정의 앱 비밀번호를 생성한다.
3. 일반 계정 비밀번호 대신 앱 비밀번호를 사용한다.

### 4.3 설정

비밀값 설치:

```bash
bash infrastructure/application/install-smtp-password.sh
```

저장 위치:

```text
/etc/zani/application/secrets/smtp_password
```

`runtime.env`:

```dotenv
NOTIFICATION_EMAIL_ENABLED=true
NOTIFICATION_EMAIL_FROM=<발신 Gmail 주소>
NOTIFICATION_APP_BASE_URL=https://i15a105.p.ssafy.io
SPRING_MAIL_HOST=smtp.gmail.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=<발신 Gmail 주소>
SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true
```

앱 비밀번호는 `SPRING_MAIL_PASSWORD` 문자열 환경 변수로 직접 쓰지 않고 Docker secret으로 주입한다.

## 5. LiveKit Server·Egress

LiveKit Cloud를 사용하지 않고 EC2에 직접 호스팅한다.

### 5.1 버전과 역할

| 구성 | 버전 | 역할 |
| --- | --- | --- |
| LiveKit Server | 1.13.1 | Room, Signal, WebRTC SFU |
| LiveKit Egress | 1.13.0 | 허용된 트랙별 녹화 |
| LiveKit Java SDK | 0.14.0 | Backend 토큰·Room·Egress API |
| LiveKit Client SDK | 2.20.1 | Frontend Room 연결 |

### 5.2 설정 파일

```text
/etc/zani/media/livekit.yaml
/etc/zani/media/egress.yaml
/opt/zani/media/compose.yaml
```

비밀값:

- LiveKit API key/secret
- Media Redis password
- Backend가 사용하는 LiveKit API key/secret과 동일해야 함

비밀을 제거한 핵심 설정:

```yaml
# livekit.yaml
port: 7880
rtc:
  tcp_port: 8801
  udp_port: 8802
  use_external_ip: true
redis:
  address: 127.0.0.1:6379
turn:
  enabled: false
```

```yaml
# egress.yaml
ws_url: ws://127.0.0.1:7880
redis:
  address: 127.0.0.1:6379
```

Egress 결과는 `/srv/zani/recordings/track-egress`에 저장한다. S3 설정은 사용하지 않는다.

## 6. Coturn

### 6.1 사용 목적

교육장처럼 WebRTC 직접 미디어 포트가 제한된 네트워크에서 TURN/TLS fallback을 제공한다.

### 6.2 설정

```text
/etc/zani/media/coturn.conf
/etc/zani/media/coturn-shared-secret
```

비밀을 제거한 핵심 설정:

```ini
tls-listening-port=8443
min-port=8500
max-port=8799
realm=i15a105.p.ssafy.io
use-auth-secret
static-auth-secret=<secret>
cert=/etc/letsencrypt/live/i15a105.p.ssafy.io/fullchain.pem
pkey=/etc/letsencrypt/live/i15a105.p.ssafy.io/privkey.pem
```

`external-ip`에는 `공인IP/사설IP` 매핑을 설정한다. TURN shared secret은 LiveKit API secret과 다른 값이다.

## 7. GitLab·Jenkins

### 7.1 GitLab 자격증명 분리

| Secret | 권한·용도 |
| --- | --- |
| `GITLAB_TOKEN` | 저장소 read 전용, 배포 checkout |
| `GITLAB_REVIEW_TOKEN` | GitLab API, MR diff 조회와 댓글 게시 |
| `GITLAB_WEBHOOK_TOKEN` | inbound Webhook 서명 검증만 수행 |

저장 위치:

```text
/etc/zani/jenkins/secrets/
```

### 7.2 Webhook

```text
Push: https://i15a105.p.ssafy.io/project/zani-dev-dispatch
MR:   https://i15a105.p.ssafy.io/project/zani-mr-review
```

- Push Webhook은 `dev` branch Push events만 활성화한다.
- MR Webhook은 Merge request events만 활성화한다.
- SSL verification을 끄지 않는다.
- Secret token은 Jenkins Job이 읽는 `GITLAB_WEBHOOK_TOKEN`과 같아야 한다.

### 7.3 Jenkins Plugin Update Center

SSAFY 환경에서 기본 Jenkins mirror 연결이 불안정해 Controller 이미지는 다음 mirror를 명시한다.

```text
JENKINS_UC=https://raw.githubusercontent.com/lework/jenkins-update-center/master/updates/huawei
JENKINS_PLUGIN_INFO=https://archives.jenkins.io/update-center/current/plugin-versions.json
```

Mirror CA 파일의 SHA-256을 Docker build에서 검증한 뒤 plugin을 설치한다. Jenkins는 반드시 로그인 필수 상태로 운영하며 익명 관리자 접근을 허용하지 않는다.

## 8. Let's Encrypt

Nginx HTTPS와 Coturn TURN/TLS가 같은 운영 도메인 인증서를 읽는다.

```text
/etc/letsencrypt/live/i15a105.p.ssafy.io/fullchain.pem
/etc/letsencrypt/live/i15a105.p.ssafy.io/privkey.pem
```

갱신 후에는 인증서 검증과 Nginx reload, Coturn 재시작 여부를 확인한다.

```bash
sudo certbot renew --dry-run
sudo nginx -t
sudo systemctl reload nginx
```

Coturn은 인증서를 프로세스 시작 시 읽으므로 갱신된 인증서 적용을 위해 컨테이너 재시작이 필요할 수 있다.

## 9. 사용하지 않는 서비스

다음 서비스는 현재 운영 구조에서 사용하지 않는다.

- AWS RDS
- AWS S3
- AWS IAM 기반 애플리케이션 자격증명
- MinIO
- Vercel
- LiveKit Cloud
- Photon Cloud
- PortOne 등 결제 서비스
- 외부 코드 컴파일·실행 서비스
- Google Cloud STT 또는 OpenAI 직접 API 계약

DB·Redis·미디어·프론트엔드·백엔드는 SSAFY EC2에 자체 호스팅하고, 녹화는 EC2 로컬 디스크에 보관한다.
