const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const DEFAULT_SCHEMA_PATH = path.join(__dirname, '..', 'config', 'review-schema.json');
const MAX_DIFF_CHARACTERS = 350_000;

function loadSchema(schemaPath = DEFAULT_SCHEMA_PATH) {
  return JSON.parse(fs.readFileSync(schemaPath, 'utf8'));
}

function buildPrompt({ context, deterministicFindings, repositoryContext }) {
  const diff = context.diff.length > MAX_DIFF_CHARACTERS
    ? `${context.diff.slice(0, MAX_DIFF_CHARACTERS)}\n\n[diff가 크기 제한으로 잘렸습니다.]`
    : context.diff;

  return [
    '당신은 GitLab Merge Request의 읽기 전용 코드 리뷰어입니다.',
    '코드를 수정하거나 명령을 실행하지 말고 제공된 diff만 검토하세요.',
    '',
    '검토 기준:',
    '- Git 컨벤션: 브랜치명과 커밋 메시지 검사 결과를 존중하고 중복 지적하지 않습니다.',
    '- 백엔드 DDD: presentation -> application -> domain, infrastructure는 안쪽 Port를 구현합니다.',
    '- 백엔드 domain은 순수 Java이며 Spring/JPA/HTTP를 사용하지 않습니다.',
    '- Domain Model과 JPA Entity를 분리하고, 다른 도메인의 infrastructure에 직접 접근하지 않습니다.',
    '- 프론트엔드 DDD: app -> presentation -> application -> domain, infrastructure는 외부 기술을 격리합니다.',
    '- 프론트엔드 domain은 React/Next/Axios/TanStack Query/Zustand/browser storage에 의존하지 않습니다.',
    '- 도메인 외부 소비자는 domains/<name>/index.ts 공개 API를 사용합니다.',
    '- 실제 버그, 보안, 오류 처리, 테스트 누락과 유지보수 위험을 검토합니다.',
    '- 확실한 병합 차단 문제만 blocker, 확인이 필요한 문제는 warning, 참고는 info로 분류합니다.',
    '- 변경된 줄에 근거가 있을 때만 file과 line을 작성합니다.',
    '- 변경 파일 내용이 첨부되어 있으니 diff와 함께 읽되, 이번 변경과 무관한 기존 결함은 지적하지 않습니다.',
    '- Every AI finding must include an integer confidence score from 0 to 100.',
    '- Return only findings with confidence 80 or higher. If evidence is insufficient, omit the finding.',
    '- Do not suggest a reusable component unless the supplied context proves it exists and is applicable.',
    '- Do not report cross-platform or external CLI compatibility concerns without supplied failure evidence or official documentation.',
    '- summary, message, suggestion의 자연어는 모두 한국어로 작성하세요. 파일 경로, 코드, 라이브러리 이름은 원문을 유지합니다.',
    '- 짧고 쉬운 단어를 사용하세요. summary는 1문장 100자 이내, message는 1문장 160자 이내, suggestion은 1문장 100자 이내로 작성하세요.',
    '- message에는 문제와 영향만, suggestion에는 바로 할 수정만 담아 같은 내용을 반복하지 마세요.',
    '',
    `기준 브랜치: ${context.baseRef}`,
    `현재 브랜치: ${context.branch}`,
    `검토 SHA: ${context.headSha}`,
    `변경 파일: ${JSON.stringify(context.changedFiles)}`,
    `커밋 메시지: ${JSON.stringify(context.commitMessages)}`,
    '',
    '결정적 검사 결과:',
    JSON.stringify(deterministicFindings, null, 2),
    '',
    '변경 diff:',
    diff,
    '',
    repositoryContext?.markdown || '## Changed files\nNo changed file content was attached.',
    '',
    '지정된 JSON Schema에 맞는 결과만 반환하세요.',
  ].join('\n');
}

function defaultInvokeClaude(args, { cwd, input }) {
  const result = spawnSync('claude', args, {
    cwd,
    input,
    encoding: 'utf8',
    maxBuffer: 12 * 1024 * 1024,
    timeout: 10 * 60 * 1000,
    windowsHide: true,
  });

  return {
    status: result.status ?? 1,
    stdout: result.stdout || '',
    stderr: result.error?.message || result.stderr || '',
  };
}

function parseJson(value, failureMessage) {
  try {
    return JSON.parse(value);
  } catch (error) {
    throw new Error(`${failureMessage}: ${error.message}`);
  }
}

function validateReview(review) {
  const validSeverity = new Set(['blocker', 'warning', 'info']);
  if (!review || typeof review !== 'object' || Array.isArray(review)) {
    throw new Error('Claude 리뷰 결과 형식이 객체가 아닙니다.');
  }
  if (typeof review.summary !== 'string' || !Array.isArray(review.findings)) {
    throw new Error('Claude 리뷰 결과 형식에 summary/findings가 없습니다.');
  }

  review.findings.forEach((finding, index) => {
    const valid = finding
      && typeof finding === 'object'
      && validSeverity.has(finding.severity)
      && typeof finding.type === 'string'
      && typeof finding.message === 'string'
      && (finding.file === undefined || typeof finding.file === 'string')
      && (finding.line === undefined || (Number.isInteger(finding.line) && finding.line > 0))
      && (finding.suggestion === undefined || typeof finding.suggestion === 'string')
      && Number.isInteger(finding.confidence)
      && finding.confidence >= 0
      && finding.confidence <= 100;
    if (!valid) throw new Error(`Claude 리뷰 finding ${index + 1}의 형식이 올바르지 않습니다.`);
  });

  return review;
}

function filterHighConfidenceFindings(review) {
  return {
    ...review,
    findings: review.findings.filter((finding) => finding.confidence >= 80),
  };
}

function reviewFromEnvelope(stdout) {
  const envelope = parseJson(stdout, 'Claude Code JSON 응답을 해석할 수 없습니다');
  if (envelope.is_error) {
    throw new Error(`Claude Code 실행 실패: ${envelope.result || '알 수 없는 오류'}`);
  }
  if (envelope.structured_output) return filterHighConfidenceFindings(validateReview(envelope.structured_output));
  if (typeof envelope.result === 'string') {
    return filterHighConfidenceFindings(
      validateReview(parseJson(envelope.result, 'Claude 리뷰 결과 형식이 JSON이 아닙니다')),
    );
  }
  return filterHighConfidenceFindings(validateReview(envelope));
}

async function runClaudeReview(input, {
  invokeClaude = defaultInvokeClaude,
  schema = loadSchema(),
} = {}) {
  const prompt = buildPrompt(input);
  const args = [
    '-p',
    '--output-format', 'json',
    '--json-schema', JSON.stringify(schema),
    '--permission-mode', 'dontAsk',
    '--no-session-persistence',
    '--no-chrome',
    '--tools', '',
  ];
  const result = invokeClaude(args, { cwd: input.context.repositoryRoot, input: prompt });
  if (result.status !== 0) {
    throw new Error(`Claude Code 실행 실패: ${String(result.stderr || result.stdout || '원인을 확인할 수 없습니다.').trim()}`);
  }
  return reviewFromEnvelope(result.stdout);
}

module.exports = {
  buildPrompt,
  filterHighConfidenceFindings,
  loadSchema,
  reviewFromEnvelope,
  runClaudeReview,
  validateReview,
};
