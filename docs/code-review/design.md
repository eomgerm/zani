# Claude Code GitLab MR Reviewer 설계

## 목적

`code-review/`는 ZANI 모노레포의 MR 변경을 먼저 점검하는 로컬 우선 코드리뷰 도구다. Git 컨벤션과 DDD 규칙은 규칙 기반으로 확실하게 검사하고, 재사용 컴포넌트·중복 구현·명백한 버그처럼 문맥 판단이 필요한 부분은 Claude Code가 검토한다.

현재는 개발자 PC에서 실행한다. Runner 또는 Jenkins를 준비한 뒤에는 같은 명령을 MR 생성·업데이트 파이프라인에 넣어 GitLab 댓글을 자동으로 게시한다.

## 폴더 구조

```text
.claude/
  agents/
    code-reviewer.md        # Claude Code 수동 검토용 역할·품질 기준

code-review/
  bin/                      # node로 실행하는 진입점
  ci/                       # Runner 연결 전 참고할 GitLab CI 예시
  config/                   # DDD 규칙과 Claude 응답 JSON 형식
  lib/                      # Git, Claude, GitLab API, 보고서 처리
  test/                     # 실제 Claude/GitLab 호출 없는 자동 테스트
  README.md                 # 팀원용 사용법

docs/code-review/
  design.md                 # 이 문서
  implementation-plan.md    # 적용·검증 순서
```

Anthropic의 공식 Code Review 플러그인이 `plugins/code-review`라는 기능 이름과 `commands/` 구조를 사용하는 점을 참고했다. 다만 이 도구는 Jenkins와 GitLab CI에서도 실행되어야 하므로, Claude 전용 역할 문서는 `.claude/agents`에 두고 실행 코드는 일반적인 `code-review/`에 둔다.

## 검토 흐름

```text
Git diff 수집
  ├─ MR 지정 시 최신 MR head ref 가져오기
  ├─ Git 브랜치·커밋 컨벤션 검사
  ├─ 프론트·백엔드 DDD 규칙 검사
  ├─ 제한된 읽기 전용 후보 문맥 수집
  └─ Claude Code 의미 검토
       └─ 80점 이상 결과만 반영
            └─ JSON 보고서 생성
                 └─ 선택 시 GitLab 요약 댓글 게시
```

Claude는 변경 diff, 결정적 검사 결과, 그리고 아래 제한 안에서 수집한 재사용 후보 파일만 받는다.

- 최대 12개 파일
- 총 최대 120KB
- 파일 하나당 최대 32KB
- `fe/src/shared/ui`에서 변경된 JSX와 이름이 맞는 후보만 선택
- 변경 파일, `.git`, `node_modules`, 빌드 산출물은 제외

GitLab에 게시하는 댓글은 한국어 요약 note 하나다. 상태와 개수, 짧은 요약, 파일·줄 위치, 문제와 수정 방법을 남긴다. Claude의 요약·문제·수정 제안은 각각 100자·160자·100자 이내의 쉬운 한국어로 제한한다.

따라서 Claude가 전체 저장소를 읽어서 토큰을 끝없이 사용하지 않는다. 후보에 실제 정의가 있을 때만 재사용 또는 중복 권고를 작성하도록 프롬프트와 `.claude` 역할 파일에 명시한다.

## 판단 기준

### 규칙 기반 차단

- 브랜치명·커밋 메시지 컨벤션
- frontend domain의 React/Next/Axios/TanStack Query/Zustand/storage 의존
- frontend 계층의 허용되지 않은 방향 의존
- 다른 domain 내부 경로 직접 접근
- backend domain의 Spring/JPA/HTTP 의존 및 계층 역전

### Claude Code 권고

- 변경으로 새로 생긴 명백한 버그, 보안, 오류 처리 누락
- 변경된 코드와 후보 파일을 비교해 확인 가능한 공통 컴포넌트 미사용
- 제공된 문맥으로 증명되는 중복 구현

Claude 결과에는 0~100 신뢰도 점수가 필수다. 80점 미만은 결과에서 제외한다. 스타일 취향, 추측성 문제, 기존 코드 문제, 린터가 확실히 잡는 항목은 댓글로 남기지 않는다.

## GitLab 댓글 안전장치

`--publish`는 `GITLAB_TOKEN`의 `api` 권한으로 GitLab REST API를 호출한다.

`--mr <IID 또는 URL>`을 지정하면 GitLab API로 실제 소스 브랜치명과 최신 SHA를 확인하고, `refs/merge-requests/<IID>/head`를 로컬 임시 ref로 가져와 검토한다. 현재 체크아웃 브랜치, staged 변경, untracked 파일은 MR 검토에 포함하지 않는다.

- 검토 SHA와 현재 MR SHA가 같을 때만 게시
- draft, 닫힌 MR은 건너뜀
- `<!-- zani-code-review:<sha> -->` 표시로 같은 SHA 중복 게시 방지
- 모든 결과는 MR note 하나에 게시하고, 파일·줄이 있는 항목도 `파일경로:줄번호`로 표기
- 토큰은 로컬 환경변수, Jenkins Credential, GitLab CI 변수 중 하나로만 전달

자동 머지는 포함하지 않는다. 코드리뷰 결과는 팀원 Approve를 보조하는 자료다.
