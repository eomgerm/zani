const SEVERITY_ORDER = Object.freeze({ blocker: 0, warning: 1, info: 2 });

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
  return `blocker ${counts.blocker}개, warning ${counts.warning}개, info ${counts.info}개`;
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
  const status = report.passed ? '✅ 통과' : '⛔ 차단';
  const lines = [
    `## Claude Code MR 리뷰: ${status}`,
    '',
    report.summary,
    '',
    `- 기준 브랜치: \`${report.baseRef}\``,
    `- 검토 커밋: \`${report.headSha || '확인 불가'}\``,
  ];

  if (report.findings.length === 0) {
    lines.push('', '지적 사항이 없습니다.');
    return lines.join('\n');
  }

  lines.push('', '### 지적 사항', '');
  for (const finding of report.findings) {
    const position = finding.file
      ? ` — \`${finding.file}${finding.line ? `:${finding.line}` : ''}\``
      : '';
    const confidence = finding.confidence === undefined ? '' : ` · 신뢰도 ${finding.confidence}/100`;
    lines.push(`- **${finding.severity.toUpperCase()} / ${finding.type}**${position}${confidence}: ${finding.message}`);
    if (finding.suggestion) lines.push(`  - 제안: ${finding.suggestion}`);
  }

  return lines.join('\n');
}

module.exports = { buildReport, normalizeFinding, renderMarkdown };
