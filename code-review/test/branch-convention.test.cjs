const assert = require('node:assert/strict');
const { execFileSync, spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const scriptPath = path.join(__dirname, '..', '..', 'scripts', 'validate-branch-name.cjs');

test('branch validation allows dev area integration branches without Jira keys', () => {
  const repositoryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'zani-branch-rule-'));
  try {
    execFileSync('git', ['init', '--quiet'], { cwd: repositoryRoot });
    execFileSync('git', ['symbolic-ref', 'HEAD', 'refs/heads/invalid-branch'], { cwd: repositoryRoot });

    for (const branch of ['dev-be', 'dev-fe', 'dev-ai']) {
      const result = spawnSync(process.execPath, [scriptPath, branch], {
        cwd: repositoryRoot,
        encoding: 'utf8',
      });
      assert.equal(result.status, 0, result.stderr);
    }
  } finally {
    fs.rmSync(repositoryRoot, { recursive: true, force: true });
  }
});
