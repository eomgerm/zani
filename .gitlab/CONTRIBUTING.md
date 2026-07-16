# 협업 규칙 (Gitmoji 기반)

커밋 메시지, 브랜치, Merge Request 규칙을 정리한 문서입니다.
커밋 메시지와 브랜치 이름 규칙은 Git hook(husky)으로 **자동 검사**됩니다. 규칙을 어기면 커밋이 막히고, 어떻게 고쳐야 하는지 안내 메시지가 출력됩니다.

## 0. 최초 1회 설정

프로젝트를 clone 받은 뒤 아래 명령을 한 번 실행하면 husky 훅이 활성화됩니다.

```bash
npm install
```

## 1. 커밋 메시지 규칙

### 형식

```
<gitmoji> <type>: <설명> (지라 키)
```

```
✨ feat: 로그인 페이지 UI 구현 (S15P11A105-123)
🐛 fix: 회원가입 시 이메일 중복 체크 오류 수정 (S15P11A105-56)
♻️ refactor: API 응답 파싱 로직 분리 (S15P11A105-78)
```

- 설명은 한글/영문 모두 가능, 60자 이내 권장
- 지라 키는 항상 맨 뒤에 괄호로 붙입니다 (지라 티켓 이름이 길어서 중간에 넣으면 가독성이 떨어지기 때문)
- **type은 반드시 소문자**로 씁니다 (`docs:` O, `Docs:` X)
- `npx gitmoji -c` 명령으로 이모지를 골라 대화형으로 커밋할 수 있습니다 (`git commit` 대신 사용)
  - `gitmoji-cli`는 기본적으로 커밋 타이틀 첫 글자를 자동으로 대문자화하는데, 이는 소문자 type 규칙과 충돌하므로 저장소 루트의 [.gitmojirc.json](../.gitmojirc.json)에서 `capitalizeTitle: false`로 꺼두었습니다. 별도 설정 없이 그대로 사용하면 됩니다.

### 지라 키 자동 완성

브랜치 이름(`type/설명-지라키`)에 이미 지라 키가 들어있으므로, 커밋 메시지에는 지라 키를 직접 안 써도 됩니다.

```bash
# feat/login-page-S15P11A105-123 브랜치에서
git commit -m "✨ feat: 로그인 페이지 UI 구현"
# → 커밋 메시지가 자동으로 "✨ feat: 로그인 페이지 UI 구현 (S15P11A105-123)" 이 됩니다
```

- `main`/`develop`처럼 지라 키가 없는 보호 브랜치에서는 자동 완성이 동작하지 않으므로 지라 키를 직접 적어야 합니다.
- 이미 메시지에 같은 지라 키가 포함돼 있으면(`--amend` 등) 중복으로 붙이지 않습니다.
- 자동 완성이 실패하거나 브랜치명이 없는 경우에도 최종적으로 커밋 메시지 검사(`commit-msg`)가 지라 키 유무를 다시 확인합니다.

### Gitmoji ↔ type 매핑

| Gitmoji | Code | Type | 의미 |
|---|---|---|---|
| ✨ | `:sparkles:` | feat | 새로운 기능 추가 |
| 🐛 | `:bug:` | fix | 버그 수정 |
| 📝 | `:memo:` | docs | 문서 추가/수정 |
| 💄 | `:lipstick:` | style | 로직 변경 없는 스타일/포맷팅 |
| ♻️ | `:recycle:` | refactor | 리팩토링 (기능 변화 없음) |
| ✅ | `:white_check_mark:` | test | 테스트 코드 추가/수정 |
| 🔧 | `:wrench:` | chore | 설정, 빌드, 패키지 매니저 등 |
| ⚡️ | `:zap:` | perf | 성능 개선 |
| 🔥 | `:fire:` | remove | 코드/파일 삭제 |
| 🚑 | `:ambulance:` | hotfix | 긴급 수정 |
| 🚀 | `:rocket:` | deploy | 배포 관련 |
| 🔀 | `:twisted_rightwards_arrows:` | merge | 브랜치 병합 |
| ⏪ | `:rewind:` | revert | 이전 커밋으로 되돌리기 |
| 🎉 | `:tada:` | init | 프로젝트/기능 최초 세팅 |
| 🎨 | `:art:` | design | UI/디자인 작업 |

> 목록에 필요한 타입이 없다면 `scripts/verify-commit-msg.cjs`의 `TYPES`/`GITMOJI` 배열과 이 표를 함께 수정해주세요.

`Merge ...`, `Revert ...` 로 시작하는 자동 생성 커밋 메시지는 검사에서 제외됩니다.

## 2. 브랜치 네이밍 규칙

### 형식

```
<type>/<설명(kebab-case, 영문 또는 한글)>-<지라 키>
```

```
feat/login-page-S15P11A105-123
fix/signup-email-validation-S15P11A105-56
hotfix/null-check-S15P11A105-45
fe/main-page-layout-S15P11A105-8
하위-작업/버그-수정-S15P11A105-45
```

- type은 고정된 목록으로 제한하지 않습니다. `feat`, `fix`처럼 커밋 타입과 맞춰 써도 되고, `fe`, `be`처럼 팀에서 편한 접두어를 써도 됩니다.
- 지라 키는 항상 브랜치 이름 맨 뒤에 붙입니다 (중간에 넣으면 브랜치 이름이 너무 길어지고 잘려 보이기 때문)
- `main`, `master`, `develop`, `dev`, `release/*` 브랜치는 검사에서 제외됩니다.
- 지라 티켓 없이 작업이 시작되는 경우는 없어야 하므로, 브랜치를 만들기 전에 지라 티켓부터 생성해주세요.
- (기존 GitLab 이슈 번호 기반 규칙은 지라 키로 완전히 대체되었습니다)

## 3. Merge Request 규칙

- **머지 대상 브랜치**: 기능/버그 브랜치는 `develop`으로 MR을 올립니다. `main`은 배포 시점에만 머지합니다.
- **제목 형식**: 커밋 메시지와 동일하게 `<gitmoji> <type>: <설명> (지라 키)`
  - 예: `✨ feat: 로그인 페이지 구현 (S15P11A105-123)`
- **템플릿 사용**: MR 생성 시 GitLab의 "Choose a template" 드롭다운에서 작업 성격에 맞는 템플릿을 선택합니다.
  - 신규 기능 → [Feature.md](merge_request_templates/Feature.md)
  - 버그 수정 → [Fix.md](merge_request_templates/Fix.md)
- **리뷰어**: 최소 1인 이상 지정, 승인(approve) 후 머지
- **머지 방식**: Squash commit 후 머지, 머지 후 소스 브랜치 삭제
- **연결된 지라 티켓**: MR 설명의 "관련 이슈"에 지라 티켓 링크(또는 키)를 반드시 작성

## 4. 자동 검사/자동 완성 구조 참고

실행 순서: `pre-commit` → `prepare-commit-msg` → `commit-msg`

- `.husky/pre-commit` → `scripts/validate-branch-name.cjs`: 현재 브랜치 이름 검사
- `.husky/prepare-commit-msg` → `scripts/prepare-commit-msg.cjs`: 브랜치 이름에서 지라 키를 추출해 커밋 메시지 끝에 자동으로 붙임
- `.husky/commit-msg` → `scripts/verify-commit-msg.cjs`: 커밋 메시지 형식(지라 키 포함) 최종 검사
- 검사에 실패하면 이유와 예시를 안내하고 커밋을 중단시킵니다.
