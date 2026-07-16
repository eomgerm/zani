#!/usr/bin/env node
// 커밋 메시지가 Gitmoji 컨벤션(<gitmoji> <type>: <subject> (지라 키))을 따르는지 검사한다.
// 규칙 문서: .gitlab/CONTRIBUTING.md

const fs = require('fs');
const TYPES = require('./commit-types.cjs');

// gitmoji-cli(npx gitmoji -c)가 붙여넣는 일부 이모지는 U+FE0F(variation selector)가
// 붙은 채로 들어오므로, 비교 전에 양쪽 모두 U+FE0F를 제거해 정규화한다.
const VARIATION_SELECTOR = /️/g;
const stripVariationSelector = (s) => s.replace(VARIATION_SELECTOR, '');

const GITMOJI = [
  '✨', '🐛', '📝', '💄', '♻️', '✅', '🔧', '⚡️', '🔥', '🚑', '🚀', '🔀', '⏪', '🎉', '🎨',
].map(stripVariationSelector);

const SKIP_PATTERNS = [/^Merge /, /^Revert /, /^fixup!/, /^squash!/];

const msgFile = process.argv[2];
const firstLine = stripVariationSelector(fs.readFileSync(msgFile, 'utf8').split('\n')[0].trim());

if (SKIP_PATTERNS.some((p) => p.test(firstLine))) {
  process.exit(0);
}

const pattern = new RegExp(
  `^(${GITMOJI.join('|')}) (${TYPES.join('|')}): .{1,60} \\([A-Z][A-Z0-9]*-\\d+\\)$`,
  'u'
);

if (!pattern.test(firstLine)) {
  console.error('');
  console.error('❌ 커밋 메시지 규칙 위반');
  console.error('');
  console.error('  형식: <gitmoji> <type>: <설명> (지라 키)');
  console.error('  예시: ✨ feat: 로그인 페이지 UI 구현 (S15P11A105-123)');
  console.error('');
  console.error(`  허용 타입: ${TYPES.join(', ')}`);
  console.error(`  허용 이모지: ${GITMOJI.join(' ')}`);
  console.error('');
  console.error('  npx gitmoji -c 로 이모지를 골라 커밋할 수 있습니다.');
  console.error('  전체 규칙: .gitlab/CONTRIBUTING.md');
  console.error('');
  process.exit(1);
}
