const assert = require('node:assert/strict');
const test = require('node:test');

const { buildReport, renderMarkdown } = require('../lib/report.cjs');

test('buildReport passes warnings and blocks blocker findings', () => {
  const warningReport = buildReport({
    baseRef: 'origin/dev',
    headSha: 'abc',
    summary: 'warning only',
    findings: [{ severity: 'warning', type: 'quality', message: 'check this' }],
  });
  const blockerReport = buildReport({
    baseRef: 'origin/dev',
    headSha: 'abc',
    summary: 'blocked',
    findings: [{ severity: 'blocker', type: 'backend-ddd', message: 'bad import' }],
  });

  assert.equal(warningReport.passed, true);
  assert.equal(blockerReport.passed, false);
});

test('buildReport normalizes finding fields and sorts by severity', () => {
  const report = buildReport({
    baseRef: 'origin/dev',
    headSha: 'abc',
    summary: '',
    findings: [
      { severity: 'info', type: 'quality', message: 'i' },
      { severity: 'BLOCKER', type: 'git-convention', message: 'b', line: '12' },
      { severity: 'unknown', type: '', message: '' },
    ],
    metadata: { branch: 'feat/x-S15P11A105-161' },
  });

  assert.deepEqual(report.findings.map((finding) => finding.severity), [
    'blocker', 'warning', 'info',
  ]);
  assert.equal(report.findings[0].line, 12);
  assert.equal(report.findings[1].type, 'code-quality');
  assert.equal(report.summary, '차단 1 · 경고 1 · 정보 1');
});

test('renderMarkdown uses concise Korean labels for positioned findings', () => {
  const markdown = renderMarkdown(buildReport({
    baseRef: 'origin/dev',
    headSha: 'abcdef123456',
    summary: 'DDD 위반 발견',
    findings: [{
      severity: 'blocker',
      type: 'frontend-ddd',
      message: 'domain에서 React를 import함',
      file: 'fe/src/domains/room/domain/room.ts',
      line: 3,
      suggestion: 'application으로 이동',
      confidence: 90,
    }],
  }));

  assert.match(markdown, /## 코드 리뷰: 차단/);
  assert.match(markdown, /차단 1 · 경고 0 · 정보 0/);
  assert.match(markdown, /fe\/src\/domains\/room\/domain\/room\.ts:3/);
  assert.match(markdown, /프론트엔드 DDD/);
  assert.match(markdown, /application으로 이동/);
  assert.match(markdown, /AI 90%/);
  assert.ok(!markdown.includes('frontend-ddd'));
  assert.ok(!markdown.includes('기준 브랜치'));
});

test('buildReport keeps Claude confidence in normalized findings', () => {
  const report = buildReport({
    findings: [{ severity: 'warning', type: 'reuse', message: 'Use Button', confidence: 85 }],
  });

  assert.equal(report.findings[0].confidence, 85);
});
