#!/usr/bin/env node
// 커밋 시점의 브랜치 이름이 <type>/<설명>-<지라 키> 규칙을 따르는지 검사한다.
// 규칙 문서: .gitlab/CONTRIBUTING.md

const { execSync } = require('child_process');

const PROTECTED_BRANCHES = ['main', 'master', 'develop', 'dev'];
const TYPES = ['feat', 'fix', 'refactor', 'docs', 'chore', 'hotfix', 'design', 'test'];

const branch = execSync('git symbolic-ref --short HEAD', { encoding: 'utf8' }).trim();

if (PROTECTED_BRANCHES.includes(branch) || branch.startsWith('release/')) {
  process.exit(0);
}

const pattern = new RegExp(`^(${TYPES.join('|')})/[a-z0-9]+(?:-[a-z0-9]+)*-[A-Z][A-Z0-9]*-\\d+$`);

if (!pattern.test(branch)) {
  console.error('');
  console.error(`❌ 브랜치 이름 규칙 위반: "${branch}"`);
  console.error('');
  console.error('  형식: <type>/<설명(kebab-case, 영문)>-<지라 키>');
  console.error('  예시: feat/login-page-S15P11A105-123, hotfix/null-check-S15P11A105-45');
  console.error('');
  console.error(`  허용 타입: ${TYPES.join(', ')}`);
  console.error('  전체 규칙: .gitlab/CONTRIBUTING.md');
  console.error('');
  process.exit(1);
}
