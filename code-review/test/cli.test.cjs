const assert = require('node:assert/strict');
const path = require('node:path');
const test = require('node:test');

const { main } = require('../bin/review.cjs');

function baseContext(overrides = {}) {
  return {
    repositoryRoot: 'C:/repo',
    baseRef: 'origin/dev',
    branch: 'feat/reviewer-S15P11A105-161',
    headSha: 'head-sha',
    changedFiles: ['code-review/lib/x.cjs'],
    diff: 'diff body',
    commitMessages: [],
    ...overrides,
  };
}

function harness({ context = baseContext(), gitFindings = [], dddFindings = [], claudeReview } = {}) {
  const state = {
    claudeCalls: 0, environmentLoads: [], repositoryContextCalls: 0, claudeInputs: [], reports: [], publications: [], logs: [], errors: [],
  };
  return {
    state,
    adapters: {
      collectContext: () => context,
      loadEnv(filePath) { state.environmentLoads.push(filePath); },
      checkGit: () => gitFindings,
      checkDdd: () => dddFindings,
      collectRepositoryContext() {
        state.repositoryContextCalls += 1;
        return { markdown: '## Reusable repository context\nButton' };
      },
      async reviewWithClaude(input) {
        state.claudeCalls += 1;
        state.claudeInputs.push(input);
        return claudeReview || { summary: 'Claude 검토 완료', findings: [] };
      },
      writeReport(output, report, repositoryRoot) {
        state.reports.push({ output, report, repositoryRoot });
      },
      getRemoteUrl: () => 'https://lab.ssafy.com/group/project.git',
      async publish(options) { state.publications.push(options); },
      log: (message) => state.logs.push(message),
      error: (message) => state.errors.push(message),
    },
  };
}

test('main writes a JSON report and returns 1 for deterministic blockers', async () => {
  const fixture = harness({
    dddFindings: [{
      severity: 'blocker', type: 'backend-ddd', message: 'bad import',
    }],
  });

  const exitCode = await main([], fixture.adapters);

  assert.equal(exitCode, 1);
  assert.equal(fixture.state.reports.length, 1);
  assert.equal(fixture.state.reports[0].report.passed, false);
  assert.equal(fixture.state.reports[0].repositoryRoot, 'C:/repo');
});

test('main skips Claude and passes when there are no changed files', async () => {
  const fixture = harness({ context: baseContext({ changedFiles: [], diff: '' }) });

  const exitCode = await main([], fixture.adapters);

  assert.equal(exitCode, 0);
  assert.equal(fixture.state.claudeCalls, 0);
  assert.match(fixture.state.reports[0].report.summary, /변경 파일/);
});

test('main gives Claude bounded repository context for reuse and duplication review', async () => {
  const fixture = harness();

  const exitCode = await main([], fixture.adapters);

  assert.equal(exitCode, 0);
  assert.equal(fixture.state.repositoryContextCalls, 1);
  assert.equal(fixture.state.claudeInputs[0].repositoryContext.markdown, '## Reusable repository context\nButton');
});

test('main loads the repository .env before reviewing or publishing', async () => {
  const fixture = harness();

  await main([], fixture.adapters);

  assert.deepEqual(fixture.state.environmentLoads, [path.join('C:/repo', '.env')]);
});

test('main publishes only when explicitly requested', async () => {
  const fixture = harness();

  const exitCode = await main(['--mr', '17', '--publish'], fixture.adapters);

  assert.equal(exitCode, 0);
  assert.equal(fixture.state.publications.length, 1);
  assert.equal(fixture.state.publications[0].mr, '17');
  assert.equal(fixture.state.publications[0].remoteUrl, 'https://lab.ssafy.com/group/project.git');
});

test('main preserves the written local report when publication fails', async () => {
  const fixture = harness();
  fixture.adapters.publish = async () => { throw new Error('GitLab denied'); };

  const exitCode = await main(['--mr', '17', '--publish'], fixture.adapters);

  assert.equal(exitCode, 1);
  assert.equal(fixture.state.reports.length, 1);
  assert.match(fixture.state.errors.join('\n'), /GitLab denied/);
});

test('main reports Claude execution failures with exit code 1', async () => {
  const fixture = harness();
  fixture.adapters.reviewWithClaude = async () => { throw new Error('Claude login required'); };

  const exitCode = await main([], fixture.adapters);

  assert.equal(exitCode, 1);
  assert.match(fixture.state.errors.join('\n'), /Claude login required/);
});
