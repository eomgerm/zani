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

## Claude는 어떤 파일을 읽나요?

**변경된 파일은 전부 읽습니다.** 파일 개수 제한과 전체 용량 제한은 없습니다. diff는 바뀐 줄만 보여주므로, 같은 파일의 전체 내용을 함께 넣어 앞뒤 문맥까지 보고 검토합니다.

파일 하나에만 256KB 상한이 있습니다. 이보다 큰 파일은 건너뛰지 않고 앞 256KB만 첨부하며, 잘렸다는 사실을 문맥과 리포트에 남깁니다. `package-lock.json`처럼 사람이 읽지 않는 생성 파일 하나가 Claude 컨텍스트와 메모리를 다 쓰는 것을 막기 위한 것으로, 저장소에서 손으로 쓴 가장 큰 파일이 65KB이므로 원본 코드는 잘리지 않습니다.

첨부하지 않고 건너뛰는 경우는 네 가지뿐이며, 이유와 함께 문맥과 리포트에 남깁니다.

- MR에서 삭제되어 더 이상 읽을 수 없는 파일
- 이미지·모델 가중치처럼 텍스트가 아닌 파일(NUL 바이트로 판별)
- 저장소 루트 밖을 가리키는 경로
- 저장소 안 경로지만 심볼릭 링크의 실제 파일이 저장소 밖인 경우

재사용·중복 검토는 여기에 더해, 변경된 JSX에서 `button`, `input`처럼 필요한 UI 종류를 찾고 `fe/src/shared/ui`의 이름이 맞는 후보 파일을 함께 넣습니다. 후보 파일에 실제 정의가 확인된 경우에만 “공통 컴포넌트를 쓰세요”라는 권고를 허용합니다. Claude의 재사용·중복 코드 판단은 신뢰도 80점 이상인 항목만 남깁니다. Git 규칙과 DDD의 기계적으로 확실한 위반은 별도 규칙 검사로 계속 차단합니다.

변경 파일 전체를 읽으면 이번 변경과 무관한 기존 결함도 눈에 들어오므로, 프롬프트에서 그런 지적은 하지 않도록 못 박아 둡니다. diff 본문에는 35만 자 상한이 따로 있습니다. 그보다 큰 diff는 잘리지만, 256KB 아래 변경 파일 내용은 잘리지 않고 그대로 들어갑니다.

읽은 파일 목록과 총 용량, 잘린 파일, 건너뛴 파일은 `code-review/output/review.json`의 `metadata.repositoryContext`에서 확인할 수 있습니다.

## 자동 실행 준비

Runner 또는 Jenkins를 연결할 때는 [`ci/gitlab-ci.example.yml`](ci/gitlab-ci.example.yml)을 참고합니다. 핵심 명령은 아래 한 줄입니다.

```bash
node code-review/bin/review.cjs --base "origin/$CI_MERGE_REQUEST_TARGET_BRANCH_NAME" --mr "$CI_MERGE_REQUEST_IID" --publish
```

CI 환경에는 `GITLAB_TOKEN`을 Jenkins Credential 또는 GitLab CI 변수로만 주입합니다. 이 예시 파일은 현재 `.gitlab-ci.yml`에 자동 포함되지 않으므로, 아직 Runner가 없는 상태에서 대기 파이프라인을 만들지 않습니다.

Jenkins에서는 이 봇이 `zani-mr-review` 잡으로 이미 연결되어 있습니다. `dev`를 대상으로 하는 MR 이벤트가 오면 Jenkins가 위 명령을 자동 실행해 요약 댓글을 게시합니다. 설정과 운영자 준비 절차는 [`../infrastructure/jenkins/README.md`](../infrastructure/jenkins/README.md)의 "Merge request review"를 참고합니다. Jenkins는 댓글 게시용 `api` 스코프 토큰(`GITLAB_REVIEW_TOKEN`)과 `CLAUDE_CODE_OAUTH_TOKEN`을 Credential로 주입하며, 실행 에이전트에는 `node`와 `claude` CLI가 있어야 합니다.

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
