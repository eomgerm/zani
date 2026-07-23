# ZANI 프론트엔드 EC2 배포

## 목적

Vercel을 사용하지 않고 Next.js 프론트엔드를 기존 SSAFY EC2에 독립 컨테이너로 배포한다. 브라우저는 기존 운영 도메인 `https://i15a105.p.ssafy.io`만 사용하며 Nginx가 경로별 upstream을 선택한다.

- `/` 및 Next.js 자산: `127.0.0.1:13000`의 프론트엔드
- `/api/`, `/v3/api-docs`, Swagger: 기존 Spring Boot
- `/rtc`: 기존 LiveKit signal
- `/project/zani-backend-dev/`: 기존 Jenkins webhook
- `/healthz`: 기존 Nginx health

프론트엔드 컨테이너는 외부 포트를 직접 공개하지 않는다. `13000`은 EC2 loopback에만 바인딩하고 외부 TLS 종료는 Nginx가 담당한다.

## 구성

- `fe/Dockerfile`: Next.js standalone 다단계 이미지
- `compose.yaml`: 프론트엔드 전용 Compose 프로젝트
- `nginx-location.conf`: 기존 HTTPS server 블록에 추가할 fallback location
- `deploy-frontend.sh`: Git SHA 이미지 빌드, 교체, health 확인, 실패 시 이전 이미지 롤백
- `smoke-frontend.sh`: 프론트엔드와 기존 API 경로 회귀 확인

`NEXT_PUBLIC_API_BASE_URL`은 기본적으로 비워 둔다. 브라우저 요청이 같은 origin의 `/api`로 전송되어 CORS와 쿠키 구성이 단순해진다.

## 배포 전 검증

저장소 루트에서 다음을 실행한다.

```bash
cd fe
npm ci
npm run lint
npm run test
npm run build

cd ../infrastructure/frontend
docker compose -f compose.yaml config
```

## EC2 최초 설치

최초 1회 아래 작업이 필요하다.

1. 검증된 저장소 checkout을 EC2에 준비한다.
2. 기존 TLS server 블록의 `location / { return 404; }`를 `nginx-location.conf`의 블록으로 교체한다. 같은 `location /`을 중복 추가하면 안 된다.
3. `nginx -t`가 성공한 경우에만 Nginx를 reload한다.
4. Spring Boot의 `FRONTEND_ORIGIN`을 `https://i15a105.p.ssafy.io`로 변경하고 Application Stack을 재기동한다.

서버 디렉터리 생성, 소유자·권한 설정 또는 기존 Nginx 파일 수정은 운영자 확인 후 수행한다. SSH, UFW, LiveKit, coturn, Gerrit 설정은 이 작업의 변경 대상이 아니다.

## 배포

배포 대상 SHA가 checkout된 깨끗한 작업 트리에서 실행한다.

```bash
./infrastructure/frontend/deploy-frontend.sh
./infrastructure/frontend/smoke-frontend.sh https://i15a105.p.ssafy.io
```

배포 이미지는 기본적으로 `zani/frontend:<40자리 Git SHA>`로 남는다. health check가 실패하면 스크립트가 직전 컨테이너 이미지로 되돌린다.

## 수동 롤백

서버에 남아 있는 이전 이미지 태그를 확인한 후 해당 태그로 컨테이너만 교체한다.

```bash
docker images --format '{{.Repository}}:{{.Tag}}' zani/frontend
FRONTEND_IMAGE=zani/frontend:<previous-sha> \
  docker compose -f infrastructure/frontend/compose.yaml up -d --no-build --no-deps frontend
```

## 운영 제약

- CPU 제한: `0.50` core
- 메모리 제한: `512 MiB`
- 컨테이너 root filesystem: read-only
- 쓰기 가능 임시 경로: `/tmp`, `/app/.next/cache`
- 로그 회전: 파일당 10 MiB, 최대 3개
- Jenkins 프론트엔드 CD는 이번 범위에 포함하지 않는다.

프론트엔드 빌드와 컨테이너 교체는 향후 LiveKit/Egress 또는 FFmpeg 후처리가 실행 중일 때 유예하도록 Jenkins 작업 상태 제어와 결합한다.
