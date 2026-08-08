# ZANI 포팅 산출물

ZANI 프로젝트의 빌드·배포와 운영 환경 재현에 필요한 제출 문서를 정리한 폴더다.

- 기준 일자: 2026-08-08
- 운영 주소: `https://i15a105.p.ssafy.io`
- 실제 비밀번호·API 키·토큰·개인 계정 정보는 포함하지 않는다.

## 산출물

| 제출 항목 | 문서 | 내용 |
| --- | --- | --- |
| 1. 빌드·배포 문서 | [`porting-manual.md`](01-porting-manual/porting-manual.md), [`environment-variables.md`](01-porting-manual/environment-variables.md) | 기술 버전, Clone, 빌드, 환경변수, 배포, DB 접속 구조와 복구 절차 |
| 2. 외부 서비스 정보 | [`external-services.md`](02-external-services/external-services.md) | Google OAuth, SSAFY GMS, SMTP, LiveKit, Coturn, GitLab·Jenkins, 인증서 |
| 3. DB 덤프 최신본 | [`zani-schema.sql`](03-db-dump/zani-schema.sql) | MySQL 8.4.10, Flyway V1~V20 적용 구조 덤프 |
| 4. 시연 시나리오 | [`demo-scenario.md`](04-demo-scenario/demo-scenario.md) | 화면별 시연 순서와 정상 결과 |

## 읽는 순서

새 환경을 구성할 때는 다음 순서로 확인한다.

1. 포팅 매뉴얼
2. 환경변수 명세
3. 외부 서비스 정보
4. DB 덤프

시연 시나리오는 배포 환경 구성이 끝난 뒤 사용한다.
