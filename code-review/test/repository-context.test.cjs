const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const { collectReviewContext, fenceFor } = require('../lib/repository-context.cjs');

function writeFile(root, relativePath, content) {
  const target = path.join(root, relativePath);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, content);
}

function withRepository(run) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zani-code-review-'));
  try {
    return run(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

function pathsOfKind(result, kind) {
  return result.files.filter((file) => file.kind === kind).map((file) => file.path);
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
      pathsOfKind(result, 'candidate'),
      ['fe/src/shared/ui/Button.tsx', 'fe/src/shared/ui/Input.tsx'],
    );
    assert.match(result.markdown, /Reusable repository context/);
    assert.match(result.markdown, /Button\.tsx/);
  });
});

test('collectReviewContext attaches the full content of every changed file', () => {
  withRepository((repositoryRoot) => {
    writeFile(repositoryRoot, 'backend/src/main/java/com/a105/zani/auth/domain/Member.java', 'class Member {}');
    writeFile(repositoryRoot, 'fe/src/domains/auth/presentation/LoginForm.tsx', '<button>Login</button>');
    writeFile(repositoryRoot, 'docs/code-review/design.md', '# design');

    const result = collectReviewContext({
      repositoryRoot,
      changedFiles: [
        'backend/src/main/java/com/a105/zani/auth/domain/Member.java',
        'fe\\src\\domains\\auth\\presentation\\LoginForm.tsx',
        'docs/code-review/design.md',
      ],
      diff: '+<button>Login</button>',
    });

    assert.deepEqual(pathsOfKind(result, 'changed'), [
      'backend/src/main/java/com/a105/zani/auth/domain/Member.java',
      'fe/src/domains/auth/presentation/LoginForm.tsx',
      'docs/code-review/design.md',
    ]);
    assert.equal(result.skipped.length, 0);
    assert.match(result.markdown, /class Member \{\}/);
    assert.match(result.markdown, /# design/);
  });
});

test('collectReviewContext applies no file-count or byte limit to changed files', () => {
  withRepository((repositoryRoot) => {
    const changedFiles = [];
    for (let index = 0; index < 20; index += 1) {
      const file = `fe/src/domains/auth/application/useCase${index}.ts`;
      writeFile(repositoryRoot, file, `// ${'a'.repeat(50 * 1024)}`);
      changedFiles.push(file);
    }

    const result = collectReviewContext({
      repositoryRoot,
      changedFiles,
      diff: '+const x = 1;',
    }, { maxFiles: 1, maxBytes: 40, maxFileBytes: 40 });

    assert.equal(pathsOfKind(result, 'changed').length, 20);
    assert.ok(result.totalBytes > 1000 * 1024);
    assert.equal(result.skipped.length, 0);
  });
});

test('collectReviewContext skips deleted, binary, and out-of-root files with a reason', () => {
  withRepository((repositoryRoot) => {
    writeFile(repositoryRoot, 'fe/public/logo.png', Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x00, 0x01]));

    const result = collectReviewContext({
      repositoryRoot,
      changedFiles: ['fe/src/removed/Old.tsx', 'fe/public/logo.png', '../outside/Secret.tsx'],
      diff: '+const x = 1;',
    });

    assert.equal(result.files.length, 0);
    assert.deepEqual(result.skipped, [
      { path: 'fe/src/removed/Old.tsx', reason: 'deleted or unreadable' },
      { path: 'fe/public/logo.png', reason: 'binary' },
      { path: '../outside/Secret.tsx', reason: 'outside the repository root' },
    ]);
    assert.match(result.markdown, /## Skipped files/);
    assert.match(result.markdown, /logo\.png`: binary/);
  });
});

test('collectReviewContext lists a changed file once and never as a reuse candidate', () => {
  withRepository((repositoryRoot) => {
    writeFile(repositoryRoot, 'fe/src/shared/ui/Button.tsx', 'export const Button = () => null;');

    const result = collectReviewContext({
      repositoryRoot,
      changedFiles: ['fe/src/shared/ui/Button.tsx', 'fe/src/shared/ui/Button.tsx'],
      diff: '+<Button />',
    });

    assert.deepEqual(result.files.map((file) => file.path), ['fe/src/shared/ui/Button.tsx']);
    assert.deepEqual(pathsOfKind(result, 'candidate'), []);
  });
});

test('fenceFor keeps attached markdown from breaking the context sections', () => {
  withRepository((repositoryRoot) => {
    writeFile(repositoryRoot, 'docs/code-review/design.md', '# design\n\n```text\nflow\n```\n');

    const result = collectReviewContext({
      repositoryRoot,
      changedFiles: ['docs/code-review/design.md'],
      diff: '+# design',
    });

    assert.equal(fenceFor('no fence here'), '```');
    assert.equal(fenceFor('```text\nflow\n```'), '````');
    assert.match(result.markdown, /````\n# design/);
  });
});
