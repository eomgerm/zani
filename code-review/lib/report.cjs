const SEVERITY_ORDER = Object.freeze({ blocker: 0, warning: 1, info: 2 });
const SEVERITY_LABELS = Object.freeze({ blocker: '차단', warning: '경고', info: '정보' });
const TYPE_LABELS = Object.freeze({
  'backend-ddd': '백엔드 DDD',
  'frontend-ddd': '프론트엔드 DDD',
  'git-convention': 'Git 규칙',
  'branch-convention': '브랜치 규칙',
  'commit-convention': '커밋 규칙',
  'component-reuse': '컴포넌트 재사용',
  'duplicate-code': '중복 코드',
  'scope-creep': '작업 범위',
  'error-handling': '오류 처리',
  security: '보안',
  test: '테스트',
  quality: '코드 품질',
  'code-quality': '코드 품질',
});

function normalizeSeverity(value) {
  const severity = String(value || '').toLowerCase();
  return Object.hasOwn(SEVERITY_ORDER, severity) ? severity : 'warning';
}

function normalizeFinding(finding = {}) {
  const line = Number.parseInt(finding.line, 10);
  const normalized = {
    severity: normalizeSeverity(finding.severity),
    type: String(finding.type || 'code-quality'),
    message: String(finding.message || '설명이 없는 지적'),
  };

  if (finding.file) normalized.file = String(finding.file).replaceAll('\\', '/');
  if (Number.isInteger(line) && line > 0) normalized.line = line;
  if (finding.suggestion) normalized.suggestion = String(finding.suggestion);
  if (Number.isInteger(finding.confidence) && finding.confidence >= 0 && finding.confidence <= 100) {
    normalized.confidence = finding.confidence;
  }

  return normalized;
}

function countSummary(findings) {
  const counts = { blocker: 0, warning: 0, info: 0 };
  for (const finding of findings) counts[finding.severity] += 1;
  return `차단 ${counts.blocker} · 경고 ${counts.warning} · 정보 ${counts.info}`;
}

function buildReport({
  baseRef = 'origin/dev',
  headSha = '',
  summary = '',
  findings = [],
  metadata = {},
} = {}) {
  const normalizedFindings = findings
    .map(normalizeFinding)
    .sort((left, right) => SEVERITY_ORDER[left.severity] - SEVERITY_ORDER[right.severity]);

  return {
    passed: !normalizedFindings.some((finding) => finding.severity === 'blocker'),
    summary: summary || countSummary(normalizedFindings),
    baseRef,
    headSha,
    findings: normalizedFindings,
    metadata,
  };
}

function renderMarkdown(report) {
  const status = report.passed ? '통과' : '차단';
  const counts = countSummary(report.findings);
  const lines = [
    `## 코드 리뷰: ${status}`,
    '',
    counts,
  ];

  if (report.summary && report.summary !== counts) lines.push(`요약: ${report.summary}`);

  if (report.findings.length === 0) {
    lines.push('', '확인할 내용이 없습니다.');
    return lines.join('\n');
  }

  lines.push('', '### 확인할 내용', '');
  for (const finding of report.findings) {
    const position = finding.file
      ? ` · \`${finding.file}${finding.line ? `:${finding.line}` : ''}\``
      : '';
    const confidence = finding.confidence === undefined ? '' : ` · AI ${finding.confidence}%`;
    const type = TYPE_LABELS[finding.type] || '기타';
    const suggestion = finding.suggestion ? ` → 수정: ${finding.suggestion}` : '';
    lines.push(`- **${SEVERITY_LABELS[finding.severity]} · ${type}**${position}${confidence}: ${finding.message}${suggestion}`);
  }

  return lines.join('\n');
}

module.exports = { buildReport, normalizeFinding, renderMarkdown };
