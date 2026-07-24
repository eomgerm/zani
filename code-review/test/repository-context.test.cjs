const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const { collectReviewContext } = require('../lib/repository-context.cjs');

function writeFile(root, relativePath, content) {
  const target = path.join(root, relativePath);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, content, 'utf8');
}

function withRepository(run) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zani-code-review-'));
  try {
    return run(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

test('collectReviewContext finds reusable UI candidates suggested by changed JSX', () => {
  withRepository((repositoryRoot) => {
    writeFile(repositoryRoot, 'fe/src/domains/auth/presentation/LoginForm.tsx', '<button>Login</button>');
    writeFile(repositoryRoot, 'fe/src/shared/ui/Button.tsx', 'export function Button() { return <button />; }');
    writeFile(repositoryRoot, 'fe/src/shared/ui/Input.tsx', 'export function Input() { return <input />; }');
    writeFile(repositoryRoot, 'fe/src/shared/ui/Toast.tsx', 'export function Toast() { return null; }');

    const result = collectReviewContext({
      repositoryRoot,
      changedFiles: ['fe/src/domains/auth/presentation/LoginForm.tsx'],
      diff: '+<button>Login</button>\n+<input name="email" />',
    });

    assert.deepEqual(
      result.files.map((file) => file.path),
      ['fe/src/shared/ui/Button.tsx', 'fe/src/shared/ui/Input.tsx'],
    );
    assert.match(result.markdown, /Reusable repository context/);
    assert.match(result.markdown, /Button\.tsx/);
  });
});

test('collectReviewContext enforces file-count and byte limits', () => {
  withRepository((repositoryRoot) => {
    writeFile(repositoryRoot, 'fe/src/shared/ui/Button.tsx', 'a'.repeat(30));
    writeFile(repositoryRoot, 'fe/src/shared/ui/Input.tsx', 'b'.repeat(30));

    const result = collectReviewContext({
      repositoryRoot,
      changedFiles: ['fe/src/domains/auth/presentation/LoginForm.tsx'],
      diff: '+<button />\n+<input />',
    }, {
      maxFiles: 1,
      maxBytes: 40,
      maxFileBytes: 40,
    });

    assert.equal(result.files.length, 1);
    assert.ok(result.totalBytes <= 40);
    assert.equal(result.truncated, true);
  });
});

test('collectReviewContext excludes changed files and files outside the repository root', () => {
  withRepository((repositoryRoot) => {
    writeFile(repositoryRoot, 'fe/src/domains/auth/presentation/LoginForm.tsx', '<button />');
    writeFile(repositoryRoot, 'fe/src/shared/ui/Button.tsx', 'export const Button = () => null;');

    const result = collectReviewContext({
      repositoryRoot,
      changedFiles: ['fe/src/domains/auth/presentation/LoginForm.tsx'],
      diff: '+<button />',
    });

    assert.ok(!result.files.some((file) => file.path.includes('LoginForm')));
    assert.ok(result.files.every((file) => !file.path.startsWith('..')));
  });
});
