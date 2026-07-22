#!/usr/bin/env node
// 커밋 시점의 브랜치 이름이 [<플랫폼>/]<커밋타입>/<설명>-<지라 키> 규칙을 따르는지 검사한다.
// 규칙 문서: .gitlab/CONTRIBUTING.md

const { execSync } = require('child_process');
const TYPES = require('./commit-types.cjs');

const PROTECTED_BRANCHES = ['main', 'master', 'develop', 'dev', 'dev-be', 'dev-fe', 'dev-ai'];
const PLATFORMS = ['fe', 'be', 'ai'];

const branch = process.argv[2] || execSync('git symbolic-ref --short HEAD', { encoding: 'utf8' }).trim();

if (PROTECTED_BRANCHES.includes(branch) || branch.startsWith('release/')) {
  process.exit(0);
}

// [fe|be|ai/](선택) + 커밋타입(commit-types.cjs와 동일) + /설명(영문 또는 한글)-지라키
const pattern = new RegExp(
  `^(?:(?:${PLATFORMS.join('|')})/)?(?:${TYPES.join('|')})/[a-z0-9가-힣-]+-[A-Z][A-Z0-9]*-\\d+$`
);

if (!pattern.test(branch)) {
  console.error('');
  console.error(`❌ 브랜치 이름 규칙 위반: "${branch}"`);
  console.error('');
  console.error('  형식: [<플랫폼>/]<커밋타입>/<설명(kebab-case, 영문 또는 한글)>-<지라 키>');
  console.error('  예시: feat/login-page-S15P11A105-123, ai/fix/버그-수정-S15P11A105-45');
  console.error('');
  console.error(`  허용 플랫폼(선택): ${PLATFORMS.join(', ')}`);
  console.error(`  허용 커밋타입: ${TYPES.join(', ')}`);
  console.error('  전체 규칙: .gitlab/CONTRIBUTING.md');
  console.error('');
  process.exit(1);
}
