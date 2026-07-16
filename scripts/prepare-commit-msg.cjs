#!/usr/bin/env node
// 브랜치 이름 끝에 붙은 지라 키를 커밋 메시지 첫 줄 끝에 자동으로 붙여준다.
// 예: feat/login-page-S15P11A105-123 브랜치에서 "✨ feat: 로그인 페이지" 라고만 입력해도
//     "✨ feat: 로그인 페이지 (S15P11A105-123)" 으로 자동 완성된다.
// 규칙 문서: .gitlab/CONTRIBUTING.md

const fs = require('fs');
const { execSync } = require('child_process');

const [, , msgFile, commitSource] = process.argv;

// merge/squash 커밋 메시지는 건드리지 않는다 (git이 이미 완성된 메시지를 넣어줌)
if (['merge', 'squash'].includes(commitSource)) {
  process.exit(0);
}

let branch;
try {
  branch = execSync('git symbolic-ref --short HEAD', { encoding: 'utf8' }).trim();
} catch {
  process.exit(0); // detached HEAD 등
}

const match = branch.match(/([A-Z][A-Z0-9]*-\d+)$/);
if (!match) {
  process.exit(0); // 브랜치 이름에 지라 키가 없음 (main/develop 등)
}
const issueKey = match[1];

const lines = fs.readFileSync(msgFile, 'utf8').split('\n');
const firstLine = lines[0];

// 이미 붙어있으면(예: --amend) 중복 방지
if (firstLine.includes(issueKey)) {
  process.exit(0);
}

// 에디터가 열려 아직 아무것도 안 쓴 상태(빈 줄)에는 붙이지 않는다
if (firstLine.trim() === '' || firstLine.trim().startsWith('#')) {
  process.exit(0);
}

lines[0] = `${firstLine} (${issueKey})`;
fs.writeFileSync(msgFile, lines.join('\n'));
