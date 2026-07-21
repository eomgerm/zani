const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const { collectGitContext, checkGitConventions } = require('../lib/git.cjs');

function gitFixture(calls) {
  const outputs = new Map([
    ['rev-parse --show-toplevel', 'C:/repo\n'],
    ['rev-parse --verify origin/dev', 'base-sha\n'],
    ['symbolic-ref --short HEAD', 'feat/reviewer-S15P11A105-161\n'],
    ['rev-parse HEAD', 'head-sha\n'],
    ['diff --name-only origin/dev...HEAD', 'fe/src/app/page.tsx\nbackend/src/A.java\n'],
    ['diff --unified=80 origin/dev...HEAD --', 'diff body\n'],
    ['diff --name-only HEAD --', 'package.json\n'],
    ['diff --unified=80 HEAD --', 'working diff\n'],
    ['ls-files --others --exclude-standard', 'code-review/new.cjs\n'],
    ['log --format=%s origin/dev..HEAD', '✨ feat: 리뷰 추가 (S15P11A105-161)\n'],
  ]);

  return (args) => {
    calls.push(args);
    const key = args.join(' ');
    if (!outputs.has(key)) throw new Error(`unexpected git call: ${key}`);
    return outputs.get(key);
  };
}

test('collectGitContext gathers a three-dot branch diff and commit metadata', () => {
  const calls = [];
  const context = collectGitContext('origin/dev', {
    runGit: gitFixture(calls),
    readFile(file) {
      assert.equal(file, 'C:/repo/code-review/new.cjs');
      return 'module.exports = {};\n';
    },
  });

  assert.equal(context.repositoryRoot, 'C:/repo');
  assert.equal(context.baseRef, 'origin/dev');
  assert.equal(context.headSha, 'head-sha');
  assert.deepEqual(context.changedFiles, [
    'fe/src/app/page.tsx',
    'backend/src/A.java',
    'package.json',
    'code-review/new.cjs',
  ]);
  assert.ok(calls.some((args) => args.includes('origin/dev...HEAD')));
  assert.match(context.diff, /working diff/);
  assert.match(context.diff, /new file: code-review\/new\.cjs/);
});

test('checkGitConventions invokes the existing branch and commit scripts', () => {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'code-review-git-test-'));
  const calls = [];
  let commitMessageContent = '';
  try {
    const findings = checkGitConventions({
      repositoryRoot: tempRoot,
      branch: 'feat/reviewer-S15P11A105-161',
      commitMessages: ['✨ feat: 리뷰 추가 (S15P11A105-161)'],
    }, {
      runScript(scriptPath, args) {
        calls.push({ scriptPath: scriptPath.replaceAll('\\', '/'), args });
        if (scriptPath.endsWith('verify-commit-msg.cjs')) {
          commitMessageContent = fs.readFileSync(args[0], 'utf8');
        }
        return { status: 0, stdout: '', stderr: '' };
      },
    });

    assert.deepEqual(findings, []);
    assert.match(calls[0].scriptPath, /scripts\/validate-branch-name\.cjs$/);
    assert.match(calls[1].scriptPath, /scripts\/verify-commit-msg\.cjs$/);
    assert.equal(commitMessageContent, '✨ feat: 리뷰 추가 (S15P11A105-161)\n');
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
});

test('checkGitConventions converts script failures into blocker findings', () => {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'code-review-git-test-'));
  let invocation = 0;
  try {
    const findings = checkGitConventions({
      repositoryRoot: tempRoot,
      branch: 'bad-branch',
      commitMessages: ['bad commit'],
    }, {
      runScript() {
        invocation += 1;
        return invocation === 1
          ? { status: 1, stdout: '', stderr: 'branch invalid' }
          : { status: 1, stdout: '', stderr: 'commit invalid' };
      },
    });

    assert.equal(findings.length, 2);
    assert.ok(findings.every((finding) => finding.severity === 'blocker'));
    assert.match(findings[0].message, /branch invalid/);
    assert.match(findings[1].message, /commit invalid/);
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
});
