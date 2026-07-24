# ZANI Frontend

Next.js(App Router) + DDD(Domain-Driven Design) 레이어 구조를 사용합니다.

## 아키텍처

프로젝트 코드는 6가지 역할로 나뉩니다.

| 레이어 | 역할 |
| --- | --- |
| `app` | 라우팅과 페이지 조립 |
| `domain` | 핵심 비즈니스 규칙 |
| `application` | 사용자 행동과 처리 흐름 (유스케이스) |
| `infrastructure` | API와 외부 기술 연결 |
| `presentation` | React 화면과 상태 관리 |
| `shared` | 여러 도메인에서 쓰는 범용 공통 코드 |

`domain / application / infrastructure / presentation` 4개 레이어는 **라우트(페이지) 단위가 아니라 도메인(비즈니스 개념) 단위**로 반복됩니다. 라우트는 "화면을 몇 개 보여줄까"의 문제이고, 도메인은 "어떤 핵심 규칙과 데이터를 다룰까"의 문제라서 서로 1:1로 대응하지 않습니다.

예를 들어 로그인 화면과 약관 동의 화면은 서로 다른 라우트(`/login`, `/terms`)지만, 둘 다 "인증"이라는 같은 비즈니스 개념을 다루므로 `domains/auth` 하나로 묶입니다. 반대로 `/home`처럼 자체 비즈니스 로직 없이 다른 도메인의 정보를 화면에 배치만 하는 라우트는 전용 도메인이 없을 수도 있습니다.

## 폴더 구조

```
src/
├── app/                        # 라우팅 (Next.js App Router)
│   ├── layout.tsx
│   ├── page.tsx
│   ├── login/
│   ├── terms/
│   ├── home/
│   ├── dashboard/
│   ├── my-lectures/
│   │   └── [sessionId]/report/
│   ├── settings/
│   ├── prejoin/[inviteCode]/
│   └── room/[sessionId]/
│
├── domains/
│   ├── auth/                   # 로그인, 약관 동의
│   │   ├── domain/
│   │   ├── application/
│   │   ├── infrastructure/
│   │   ├── presentation/
│   │   └── index.ts            # 도메인 공개 API (외부는 이 파일을 통해서만 접근)
│   │
│   ├── lecture/                # 내 강의실, 실시간 강의실, 입장 전 점검, 강의 리포트
│   │   ├── domain/
│   │   ├── application/
│   │   ├── infrastructure/
│   │   ├── presentation/
│   │   └── index.ts
│   │
│   └── user/                   # 대시보드, 계정 설정
│       ├── domain/
│       ├── application/
│       ├── infrastructure/
│       ├── presentation/
│       └── index.ts
│
└── shared/
    ├── api/                     # httpClient 등 공통 API 클라이언트
    ├── ui/                      # Button, Input 등 공통 UI 컴포넌트
    ├── lib/                     # 범용 유틸 함수
    └── types/                   # 범용 타입
```

## 현재 라우트 ↔ 도메인 매핑

| 라우트 | 도메인 |
| --- | --- |
| `/login` | `domains/auth` |
| `/terms` | `domains/auth` |
| `/home` | 없음 (도메인 로직 없이 조립만) |
| `/dashboard` | `domains/user` |
| `/my-lectures` | `domains/lecture` |
| `/my-lectures/[sessionId]/report` | `domains/lecture` |
| `/settings` | `domains/user` |
| `/prejoin/[inviteCode]` | `domains/lecture` |
| `/room/[sessionId]` | `domains/lecture` |

새 기능을 추가할 때 기존 도메인 중 하나에 속하면 그 도메인에 코드를 추가하고, 어디에도 속하지 않는 새로운 비즈니스 개념이면 `domains/` 아래 새 도메인을 만듭니다. 현재 각 도메인 레이어와 `shared`의 하위 폴더는 아직 내용이 없어 `.gitkeep`으로만 채워져 있습니다.

## 주요 라이브러리

| 라이브러리 | 용도 |
| --- | --- |
| `axios` | `shared/api`의 공통 HTTP 클라이언트, 도메인별 `infrastructure`에서 사용 |
| `livekit-client`, `@livekit/components-react` | `/prejoin`, `/room`의 실시간 화상 강의(WebRTC) 연결 |
| `vitest`, `jsdom` | 단위 테스트 실행기 |
| `@testing-library/react`, `@testing-library/jest-dom` | 컴포넌트 렌더링 테스트 |

## 개발

```bash
npm install
npm run dev
```

## 테스트

```bash
npm run test        # 전체 테스트 1회 실행
npm run test:watch  # 변경 감지하며 반복 실행
```

테스트 파일은 대상 코드 옆에 `*.test.ts(x)`로 둡니다 (예: `src/app/home/page.tsx` → `src/app/home/page.test.tsx`).

## API 타입 생성

백엔드 OpenAPI 계약(`backend/src/main/resources/contracts/openapi/zani.yaml`)이 API 타입의 단일 소스입니다. 백엔드 API가 바뀌면 계약 파일을 먼저 고치고, 아래 명령으로 타입을 다시 생성합니다.

```bash
npm run generate:types
```

`src/shared/types/api.d.ts`가 자동 생성되며, 직접 수정하지 않습니다 (파일 상단에 경고 주석이 있습니다).
