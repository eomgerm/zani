#!/usr/bin/env node
// 커밋 시점의 브랜치 이름이 <type>/<설명>-<지라 키> 규칙을 따르는지 검사한다.
// 규칙 문서: .gitlab/CONTRIBUTING.md

const { execSync } = require('child_process');

const PROTECTED_BRANCHES = ['main', 'master', 'develop', 'dev'];

const branch = execSync('git symbolic-ref --short HEAD', { encoding: 'utf8' }).trim();

if (PROTECTED_BRANCHES.includes(branch) || branch.startsWith('release/')) {
  process.exit(0);
}

// type은 더 이상 고정 목록으로 제한하지 않는다 (fe/, be/ 등 팀에서 쓰는 어떤 접두어든 허용)
// 설명에는 영문뿐 아니라 한글(가-힣)도 쓸 수 있다
const pattern = /^[a-z0-9가-힣-]+\/[a-z0-9가-힣-]+-[A-Z][A-Z0-9]*-\d+$/;

if (!pattern.test(branch)) {
  console.error('');
  console.error(`❌ 브랜치 이름 규칙 위반: "${branch}"`);
  console.error('');
  console.error('  형식: <type>/<설명(kebab-case, 영문 또는 한글)>-<지라 키>');
  console.error('  예시: feat/login-page-S15P11A105-123, 하위-작업/버그-수정-S15P11A105-45');
  console.error('');
  console.error('  전체 규칙: .gitlab/CONTRIBUTING.md');
  console.error('');
  process.exit(1);
}
