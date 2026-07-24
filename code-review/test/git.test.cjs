const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const { collectGitContext, checkGitConventions, fetchMergeRequestHead } = require('../lib/git.cjs');

function gitFixture(calls) {
  const outputs = new Map([
    ['rev-parse --show-toplevel', 'C:/repo\n'],
    ['rev-parse --verify origin/dev', 'base-sha\n'],
    ['rev-parse --verify HEAD', 'head-sha\n'],
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

test('collectGitContext reviews a fetched MR ref without local working changes', () => {
  const calls = [];
  const outputs = new Map([
    ['rev-parse --show-toplevel', 'C:/repo\n'],
    ['rev-parse --verify origin/dev', 'base-sha\n'],
    ['rev-parse --verify HEAD', 'head-sha\n'],
    ['rev-parse --verify refs/zani-review/mr/13', 'mr-head-sha\n'],
    ['rev-parse refs/zani-review/mr/13', 'mr-head-sha\n'],
    ['diff --name-only origin/dev...refs/zani-review/mr/13', 'backend/src/A.java\n'],
    ['diff --unified=80 origin/dev...refs/zani-review/mr/13 --', 'mr diff\n'],
    ['log --format=%s origin/dev..refs/zani-review/mr/13', '✨ feat: MR 변경 (S15P11A105-13)\n'],
  ]);
  const context = collectGitContext('origin/dev', {
    headRef: 'refs/zani-review/mr/13',
    branch: 'be/feat/example-S15P11A105-13',
    includeWorkingTree: false,
    runGit(args) {
      calls.push(args);
      const output = outputs.get(args.join(' '));
      if (output === undefined) throw new Error(`unexpected git call: ${args.join(' ')}`);
      return output;
    },
  });

  assert.equal(context.branch, 'be/feat/example-S15P11A105-13');
  assert.equal(context.headSha, 'mr-head-sha');
  assert.deepEqual(context.changedFiles, ['backend/src/A.java']);
  assert.match(context.diff, /mr diff/);
  assert.ok(!calls.some((args) => args.includes('HEAD')));
  assert.ok(!calls.some((args) => args[0] === 'ls-files'));
});

test('fetchMergeRequestHead fetches and verifies the requested MR head', () => {
  const calls = [];
  const headRef = fetchMergeRequestHead({
    iid: '13',
    repositoryRoot: 'C:/repo',
    expectedSha: 'mr-head-sha',
  }, {
    runGit(args, options) {
      calls.push({ args, options });
      if (args[0] === 'fetch') return '';
      if (args.join(' ') === 'rev-parse refs/zani-review/mr/13') return 'mr-head-sha\n';
      throw new Error(`unexpected git call: ${args.join(' ')}`);
    },
  });

  assert.equal(headRef, 'refs/zani-review/mr/13');
  assert.deepEqual(calls[0].args, [
    'fetch', '--no-tags', 'origin', 'refs/merge-requests/13/head:refs/zani-review/mr/13',
  ]);
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
    assert.deepEqual(calls[0].args, ['feat/reviewer-S15P11A105-161']);
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
