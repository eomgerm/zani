# Claude Code GitLab MR Reviewer

이 폴더는 MR의 변경 코드를 검토하는 팀 도구입니다. 사람이 리뷰하기 전에 Git 규칙, DDD 구조, 재사용 가능한 컴포넌트, 중복 구현 가능성을 확인하고 결과를 JSON으로 만듭니다. `--publish`를 붙이면 그 결과를 GitLab MR의 요약 댓글과 코드 줄별 댓글로 게시합니다.

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

`Logged in` 상태여야 합니다. Claude OAuth 토큰을 저장소, `.env`, GitLab 변수에 복사하지 않습니다.

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

GitLab에서 **Personal Access Token**을 만들 때 `api` 권한을 부여합니다. 토큰은 현재 터미널에서만 설정합니다.

```powershell
$env:GITLAB_TOKEN = "발급받은-토큰"
npm.cmd run review:mr -- --mr 17 --publish
```

MR 전체 URL도 사용할 수 있습니다.

```powershell
npm.cmd run review:mr -- --mr https://lab.ssafy.com/group/project/-/merge_requests/17 --publish
```

게시 전에는 MR의 최신 SHA와 검토한 SHA가 같은지 확인합니다. 다르면 오래된 결과를 게시하지 않습니다. 같은 SHA에 이 도구가 이미 댓글을 달았다면 중복 댓글을 건너뜁니다. Draft 또는 닫힌 MR도 게시하지 않습니다.

게시되는 요약 댓글 예시는 아래와 같습니다.

```md
## Claude Code MR 리뷰: 차단

blocker 1개, warning 2개, info 0개

### 지적 사항

- **BLOCKER / backend-ddd** · `backend/.../domain/User.java:24`: domain에서 JPA를 직접 참조합니다.
  - 제안: Port는 domain에 두고 JPA 구현은 infrastructure로 옮기세요.
- **WARNING / component-reuse** · `fe/.../LoginForm.tsx:18` · 신뢰도 91/100: 공통 Button 컴포넌트를 재사용할 수 있습니다.
```

파일과 줄 번호가 있는 항목은 해당 줄에 discussion 댓글도 하나씩 달립니다.

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
