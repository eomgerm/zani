# 코드리뷰 도구 적용 순서

## 지금 할 수 있는 일: 개발자 로컬 검토

1. `npm.cmd install`
2. `claude auth status`로 Claude Code 로그인 확인
3. 작업 브랜치에서 `npm.cmd run review:mr`
4. JSON 결과의 blocker를 먼저 해결
5. MR을 올린 뒤 `GITLAB_TOKEN`을 설정하고 `--mr <IID> --publish` 실행

이 단계에서는 MR 작성자의 노트북이 실행 위치다. 댓글이 달리면 사람이 결과를 확인하고 팀 규칙대로 Approve와 머지를 진행한다.

## Runner 또는 Jenkins 준비 후: 자동 댓글

1. 실행 서버에 Node.js, Git, Claude Code를 설치하고 Claude Code 로그인을 완료한다.
2. GitLab `api` 권한 토큰을 Jenkins Credential 또는 GitLab CI의 masked 변수 `GITLAB_TOKEN`으로 등록한다.
3. [`code-review/ci/gitlab-ci.example.yml`](../../code-review/ci/gitlab-ci.example.yml)의 job을 실제 파이프라인에 복사한다.
4. MR 생성·업데이트 이벤트에서 아래 명령이 실행되는지 확인한다.

```bash
npm ci
npm run test:code-review
node code-review/bin/review.cjs --base "origin/$CI_MERGE_REQUEST_TARGET_BRANCH_NAME" --mr "$CI_MERGE_REQUEST_IID" --publish
```

5. 테스트용 MR을 한 개 열어 파일·줄 정보가 포함된 요약 note가 한 번만 달리는지 확인한다.

현재 GitLab 프로젝트에는 Runner가 없으므로, 이 예시는 `.gitlab-ci.yml`에 자동 적용하지 않는다. Runner를 만든 뒤 팀에서 활성화 시점을 정하면 그때 실제 CI 설정을 추가한다.

## 검증 명령

```powershell
npm.cmd run test:code-review
node --check code-review/bin/review.cjs
npm.cmd run review:mr -- --base origin/dev --output code-review/output/local-review.json
```

마지막 명령은 GitLab 댓글을 게시하지 않는다. Claude 로그인과 로컬 검토 결과만 확인한다.
