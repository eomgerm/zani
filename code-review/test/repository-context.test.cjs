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

test('collectReviewContext keeps only the beginning of a file over the per-file limit', () => {
  withRepository((repositoryRoot) => {
    writeFile(repositoryRoot, 'fe/package-lock.json', `{"start":true,${'"pad":0,'.repeat(60 * 1024)}}`);
    writeFile(repositoryRoot, 'fe/src/domains/auth/application/useLogin.ts', 'export const useLogin = () => null;');

    const result = collectReviewContext({
      repositoryRoot,
      changedFiles: ['fe/package-lock.json', 'fe/src/domains/auth/application/useLogin.ts'],
      diff: '+const x = 1;',
    });

    const [lock, source] = result.files;
    assert.equal(lock.bytes, 256 * 1024);
    assert.ok(lock.originalBytes > lock.bytes);
    assert.ok(lock.content.startsWith('{"start":true,'));
    assert.equal(result.skipped.length, 0);
    assert.match(result.markdown, /first 262144 of \d+ bytes; the rest is not attached/);

    // 상한 아래 파일은 그대로 첨부하므로 잘렸다는 표시를 남기지 않는다.
    assert.equal(source.originalBytes, undefined);
    assert.match(result.markdown, /### `fe\/src\/domains\/auth\/application\/useLogin\.ts`\n/);
  });
});

// Windows는 관리자 권한 없이 파일 심볼릭 링크를 만들 수 없으므로 디렉터리 junction 으로 검증한다.
// junction 타입은 다른 OS에서 무시되어 일반 심볼릭 링크가 되므로 같은 테스트가 어디서나 돈다.
function linkDirectory(target, linkPath) {
  fs.mkdirSync(path.dirname(linkPath), { recursive: true });
  fs.symlinkSync(target, linkPath, 'junction');
}

test('collectReviewContext skips a link whose real path escapes the repository', () => {
  withRepository((repositoryRoot) => {
    const outsideRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'zani-outside-'));
    try {
      fs.writeFileSync(path.join(outsideRoot, 'id_rsa'), 'PRIVATE KEY');
      writeFile(repositoryRoot, 'fe/src/shared/ui/Button.tsx', 'export const Button = () => null;');
      linkDirectory(outsideRoot, path.join(repositoryRoot, 'infrastructure/linked'));

      const result = collectReviewContext({
        repositoryRoot,
        changedFiles: ['infrastructure/linked/id_rsa', 'fe/src/shared/ui/Button.tsx'],
        diff: '+const x = 1;',
      });

      assert.deepEqual(pathsOfKind(result, 'changed'), ['fe/src/shared/ui/Button.tsx']);
      assert.deepEqual(result.skipped, [
        { path: 'infrastructure/linked/id_rsa', reason: 'symlink outside the repository root' },
      ]);
      assert.doesNotMatch(result.markdown, /PRIVATE KEY/);
    } finally {
      fs.rmSync(outsideRoot, { recursive: true, force: true });
    }
  });
});

test('collectReviewContext reads a link that stays inside the repository', () => {
  withRepository((repositoryRoot) => {
    writeFile(repositoryRoot, 'fe/src/generated/Button.tsx', 'export const Button = () => null;');
    linkDirectory(path.join(repositoryRoot, 'fe/src/generated'), path.join(repositoryRoot, 'fe/src/mirror'));

    const result = collectReviewContext({
      repositoryRoot,
      changedFiles: ['fe/src/mirror/Button.tsx'],
      diff: '+const x = 1;',
    });

    assert.deepEqual(pathsOfKind(result, 'changed'), ['fe/src/mirror/Button.tsx']);
    assert.equal(result.skipped.length, 0);
    assert.match(result.markdown, /export const Button/);
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
