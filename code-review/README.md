# Claude Code GitLab MR Reviewer

이 폴더는 MR의 변경 코드를 검토하는 팀 도구입니다. 사람이 리뷰하기 전에 Git 규칙, DDD 구조, 재사용 가능한 컴포넌트, 중복 구현 가능성을 확인하고 결과를 JSON으로 만듭니다. `--publish`를 붙이면 그 결과를 파일·줄 번호가 포함된 GitLab MR 요약 댓글 하나로 게시합니다.

## 누가, 어디서 실행하나요?

지금은 MR 작성자가 자신의 노트북에서 실행합니다. Jenkins 또는 GitLab Runner를 연결한 뒤에는 MR 생성·업데이트 이벤트가 이 명령을 자동 실행합니다. 실행 위치만 바뀌며 같은 명령과 같은 규칙을 사용합니다.

```text
MR 변경 → code-review 실행 → Claude Code 검토 → GitLab 댓글 게시
```

자동 머지는 이 도구의 범위가 아닙니다. 댓글과 테스트 결과를 확인한 뒤 팀원의 Approve를 받아 머지합니다.

## 처음 한 번 준비

1. 저장소 루트에서 `npm install`을 실행합니다. PowerShell 실행 정책 때문에 `npm`이 막히면 `npm.cmd install`을 사용합니다.
2. Claude Code에 SSAFY에서 제공한 Claude.ai 계정으로 로그인합니다.

```powershell
claude auth status
```

`Logged in` 상태여야 합니다. CI나 자동 실행 환경에서는 로컬 `.env` 또는 비밀 변수에 `CLAUDE_CODE_OAUTH_TOKEN`을 설정해 로그인 대신 인증할 수 있습니다. 토큰은 Git에 커밋하지 않습니다.

## 로컬 검토

MR을 열기 전에도 실행할 수 있습니다.

```powershell
npm.cmd run review:mr
```

기본 비교 대상은 `origin/dev`이며, 결과는 `code-review/output/review.json`에 저장됩니다. 커밋한 변경뿐 아니라 staged, unstaged, 새 파일도 포함합니다.

```powershell
npm.cmd run review:mr -- --base origin/dev --output code-review/output/my-review.json
```

종료 코드 `0`은 blocker 없음, `1`은 blocker 또는 실행 오류입니다. warning과 info는 머지를 막지 않지만 검토해야 할 권고입니다.

## GitLab MR에 댓글 게시

GitLab에서 **Personal Access Token**을 만들 때 `api` 권한을 부여합니다. 저장소 루트의 `.env`에 아래처럼 한 줄을 넣으면 실행할 때 자동으로 읽습니다. `.env`는 Git 추적 대상이 아닙니다.

```text
GITLAB_TOKEN=발급받은-토큰
```

이미 PowerShell·Jenkins·GitLab CI에 `GITLAB_TOKEN`이 설정되어 있으면 그 값이 `.env`보다 우선합니다. 토큰은 채팅, 코드, 커밋 메시지에 넣지 않습니다.

```powershell
npm.cmd run review:mr -- --mr 17
```

`--mr`를 지정하면 현재 체크아웃 브랜치를 사용하지 않습니다. GitLab에서 해당 MR의 최신 소스 커밋과 브랜치명을 가져와 임시 ref에서 검토하므로, 다른 팀원의 MR도 브랜치를 직접 바꾸지 않고 검토할 수 있습니다.

MR 전체 URL도 사용할 수 있습니다.

```powershell
npm.cmd run review:mr -- --mr https://lab.ssafy.com/group/project/-/merge_requests/17
```

게시 전에는 MR의 최신 SHA와 검토한 SHA가 같은지 확인합니다. 다르면 오래된 결과를 게시하지 않습니다. 같은 SHA에 이 도구가 이미 댓글을 달았다면 중복 댓글을 건너뜁니다. Draft 또는 닫힌 MR도 게시하지 않습니다.

게시되는 요약 댓글 예시는 아래와 같습니다.

```md
## 코드 리뷰: 차단

차단 1 · 경고 2 · 정보 0
요약: 도메인 계층에서 JPA를 직접 사용합니다.

### 확인할 내용

- **차단 · 백엔드 DDD** · `backend/.../domain/User.java:24`: domain에서 JPA를 사용합니다. → 수정: JPA 구현을 infrastructure로 옮기세요.
- **경고 · 컴포넌트 재사용** · `fe/.../LoginForm.tsx:18` · AI 91%: 공통 Button을 쓸 수 있습니다. → 수정: 공통 Button을 사용하세요.
```

파일과 줄 번호가 있는 항목도 줄별 discussion을 만들지 않고, 위 요약 댓글에 `파일경로:줄번호`로 표시합니다. 이 방식은 변경되지 않은 줄을 지적해도 GitLab diff 위치 오류 없이 결과를 남길 수 있습니다.

댓글의 자연어는 모두 한국어로 작성합니다. Claude 응답은 요약 100자, 문제 160자, 수정 제안 100자로 제한해 불필요하게 긴 댓글과 토큰 사용을 줄입니다.

## 재사용·중복 코드 검토는 어떻게 동작하나요?

Claude에게 저장소 전체를 무제한으로 읽히지 않습니다. 변경된 JSX에서 `button`, `input`처럼 필요한 UI 종류를 찾고, `fe/src/shared/ui`의 관련 후보만 읽어서 검토 문맥에 넣습니다.

- 최대 12개 파일
- 총 최대 120KB
- 파일 하나당 최대 32KB
- `.git`, `node_modules`, 빌드 결과물은 제외
- 후보 파일에 실제로 확인된 경우에만 “공통 컴포넌트를 쓰세요”라는 권고를 허용

이 제한 덕분에 토큰 사용량이 MR 크기와 함께 무제한으로 커지지 않습니다. Claude의 재사용·중복 코드 판단은 신뢰도 80점 이상인 항목만 남깁니다. Git 규칙과 DDD의 기계적으로 확실한 위반은 별도 규칙 검사로 계속 차단합니다.

## 자동 실행 준비

Runner 또는 Jenkins를 연결할 때는 [`ci/gitlab-ci.example.yml`](ci/gitlab-ci.example.yml)을 참고합니다. 핵심 명령은 아래 한 줄입니다.

```bash
node code-review/bin/review.cjs --base "origin/$CI_MERGE_REQUEST_TARGET_BRANCH_NAME" --mr "$CI_MERGE_REQUEST_IID" --publish
```

CI 환경에는 `GITLAB_TOKEN`을 Jenkins Credential 또는 GitLab CI 변수로만 주입합니다. 이 예시 파일은 현재 `.gitlab-ci.yml`에 자동 포함되지 않으므로, 아직 Runner가 없는 상태에서 대기 파이프라인을 만들지 않습니다.

## 테스트

```powershell
npm.cmd run test:code-review
```

테스트는 Claude Code와 GitLab API를 실제 호출하지 않습니다.

## 폴더 안내

- `bin/`: 실행 명령
- `config/`: DDD 규칙과 Claude JSON 응답 형식
- `lib/`: Git, 규칙 검사, Claude, GitLab 댓글 처리 코드
- `test/`: 자동 테스트
- `ci/`: Runner를 연결할 때 복사할 예시 설정
- `.claude/agents/code-reviewer.md`: Claude Code에서 수동 리뷰할 때도 같은 기준을 쓰게 하는 역할 정의

설계 배경은 [`docs/code-review/design.md`](../docs/code-review/design.md), 적용 순서는 [`docs/code-review/implementation-plan.md`](../docs/code-review/implementation-plan.md)에서 확인할 수 있습니다.
