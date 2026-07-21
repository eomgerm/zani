const assert = require('node:assert/strict');
const test = require('node:test');

const { buildPrompt, loadSchema, runClaudeReview } = require('../lib/claude.cjs');

const input = {
  context: {
    repositoryRoot: 'C:/repo',
    baseRef: 'origin/dev',
    branch: 'feat/reviewer-S15P11A105-161',
    headSha: 'abc123',
    changedFiles: ['fe/src/app/page.tsx'],
    commitMessages: ['✨ feat: 리뷰 추가 (S15P11A105-161)'],
    diff: 'diff --git a/fe/src/app/page.tsx b/fe/src/app/page.tsx\n+const x = 1;',
  },
  deterministicFindings: [],
};

const validReview = {
  summary: '검토 완료',
  findings: [{
    severity: 'warning',
    type: 'code-quality',
    message: '이름을 더 명확하게 바꾸세요.',
    file: 'fe/src/app/page.tsx',
    line: 1,
    confidence: 90,
  }],
};

test('checked-in schema avoids a draft declaration unsupported by Claude Code', () => {
  assert.equal(loadSchema().$schema, undefined);
});

test('buildPrompt includes the diff, DDD boundaries, and deterministic results', () => {
  const prompt = buildPrompt(input);

  assert.match(prompt, /origin\/dev/);
  assert.match(prompt, /presentation -> application -> domain/);
  assert.match(prompt, /app -> presentation -> application -> domain/);
  assert.match(prompt, /diff --git/);
  assert.match(prompt, /결정적 검사 결과/);
});

test('buildPrompt includes bounded read-only repository context and confidence rules', () => {
  const prompt = buildPrompt({
    ...input,
    repositoryContext: {
      markdown: '## Reusable repository context\n### `fe/src/shared/ui/Button.tsx`\nexport function Button() {}',
    },
  });

  assert.match(prompt, /Reusable repository context/);
  assert.match(prompt, /Button\.tsx/);
  assert.match(prompt, /80/);
  assert.match(prompt, /Do not suggest a reusable component unless the supplied context proves it exists/);
  assert.match(prompt, /Do not report cross-platform or external CLI compatibility concerns without supplied failure evidence or official documentation/);
});

test('runClaudeReview invokes Claude without tools and parses structured_output', async () => {
  const calls = [];
  const review = await runClaudeReview(input, {
    invokeClaude(args, options) {
      calls.push({ args, options });
      return {
        status: 0,
        stdout: JSON.stringify({
          type: 'result',
          subtype: 'success',
          is_error: false,
          result: '',
          structured_output: validReview,
        }),
        stderr: '',
      };
    },
  });

  assert.deepEqual(review, validReview);
  assert.ok(calls[0].args.includes('--json-schema'));
  const toolsIndex = calls[0].args.indexOf('--tools');
  assert.equal(calls[0].args[toolsIndex + 1], '');
  assert.equal(calls[0].options.cwd, 'C:/repo');
  assert.match(calls[0].options.input, /diff --git/);
  assert.ok(!calls[0].args.some((arg) => arg.includes('diff --git')));
});

test('runClaudeReview accepts JSON encoded in the result field', async () => {
  const review = await runClaudeReview(input, {
    invokeClaude() {
      return {
        status: 0,
        stdout: JSON.stringify({
          type: 'result',
          subtype: 'success',
          is_error: false,
          result: JSON.stringify(validReview),
        }),
        stderr: '',
      };
    },
  });

  assert.deepEqual(review, validReview);
});

test('runClaudeReview drops AI findings below the 80 confidence threshold', async () => {
  const review = await runClaudeReview(input, {
    invokeClaude() {
      return {
        status: 0,
        stdout: JSON.stringify({
          type: 'result',
          structured_output: {
            summary: 'two candidates',
            findings: [
              { ...validReview.findings[0], confidence: 90 },
              { ...validReview.findings[0], confidence: 50, message: 'uncertain finding' },
            ],
          },
        }),
        stderr: '',
      };
    },
  });

  assert.equal(review.findings.length, 1);
  assert.equal(review.findings[0].confidence, 90);
});

test('runClaudeReview rejects execution and schema errors distinctly', async () => {
  await assert.rejects(
    () => runClaudeReview(input, {
      invokeClaude: () => ({ status: 1, stdout: '', stderr: '로그인이 필요합니다.' }),
    }),
    /로그인이 필요/,
  );

  await assert.rejects(
    () => runClaudeReview(input, {
      invokeClaude: () => ({
        status: 0,
        stdout: JSON.stringify({ type: 'result', result: '{"summary":1}' }),
        stderr: '',
      }),
    }),
    /형식/,
  );
});
