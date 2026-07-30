const fs = require('node:fs');
const path = require('node:path');

const COMPONENT_BY_NATIVE_TAG = Object.freeze({
  button: 'Button',
  dialog: 'Dialog',
  form: 'Form',
  input: 'Input',
  select: 'Select',
  textarea: 'Textarea',
});

const SKIPPED_DIRECTORIES = new Set([
  '.git', '.idea', '.next', 'coverage', 'dist', 'node_modules', 'output', 'build',
]);
const SOURCE_FILE_PATTERN = /\.(?:cjs|cts|js|jsx|mjs|mts|ts|tsx)$/iu;

// lock 파일처럼 사람이 읽지 않는 생성 파일 하나가 Claude 컨텍스트와 메모리를 다 쓰지 않도록
// 파일당 상한을 둔다. 저장소에서 손으로 쓴 가장 큰 파일이 65KB이므로 원본 코드는 잘리지 않는다.
const MAX_FILE_BYTES = 256 * 1024;

function normalizePath(value) {
  return value.replaceAll('\\', '/');
}

function isInsideRepository(repositoryRoot, candidate) {
  const relative = path.relative(repositoryRoot, candidate);
  return relative && !relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative);
}

function listSourceFiles(repositoryRoot, directory = repositoryRoot) {
  const entries = fs.readdirSync(directory, { withFileTypes: true });
  return entries.flatMap((entry) => {
    const absolutePath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      return SKIPPED_DIRECTORIES.has(entry.name) ? [] : listSourceFiles(repositoryRoot, absolutePath);
    }
    if (!entry.isFile() || !SOURCE_FILE_PATTERN.test(entry.name) || !isInsideRepository(repositoryRoot, absolutePath)) {
      return [];
    }
    return [normalizePath(path.relative(repositoryRoot, absolutePath))];
  });
}

function requestedComponentNames(diff) {
  const names = new Set();
  const jsxTags = String(diff || '').matchAll(/<([A-Za-z][A-Za-z0-9]*)\b/gu);
  for (const [, tag] of jsxTags) {
    const component = COMPONENT_BY_NATIVE_TAG[tag.toLowerCase()];
    if (component) names.add(component);
    if (/^[A-Z]/u.test(tag)) names.add(tag);
  }

  const sharedImports = String(diff || '').matchAll(/@\/shared\/ui\/([A-Za-z0-9_-]+)/gu);
  for (const [, component] of sharedImports) names.add(component);

  return [...names];
}

function componentNameForPath(file) {
  const normalized = normalizePath(file);
  const filename = path.posix.basename(normalized).replace(/\.[^.]+$/u, '');
  if (filename === 'index') return path.posix.basename(path.posix.dirname(normalized));
  return filename;
}

function candidatePaths(context, files = listSourceFiles(context.repositoryRoot)) {
  const changed = new Set((context.changedFiles || []).map(normalizePath));
  const names = requestedComponentNames(context.diff);
  const priority = new Map(names.map((name, index) => [name.toLowerCase(), index]));

  return files
    .map(normalizePath)
    .filter((file) => !changed.has(file))
    .filter((file) => file.startsWith('fe/src/shared/ui/'))
    .filter((file) => priority.has(componentNameForPath(file).toLowerCase()))
    .sort((left, right) => {
      const priorityDifference = priority.get(componentNameForPath(left).toLowerCase())
        - priority.get(componentNameForPath(right).toLowerCase());
      return priorityDifference || left.localeCompare(right);
    });
}

// 변경 파일에는 .md처럼 자체 코드 펜스를 가진 파일도 포함되므로, 내용보다 긴 펜스를 사용해
// 첨부한 파일이 문맥 구조를 깨지 않게 한다.
function fenceFor(content) {
  const runs = String(content).matchAll(/`{3,}/gu);
  let longest = 2;
  for (const [run] of runs) longest = Math.max(longest, run.length);
  return '`'.repeat(longest + 1);
}

function realPathOrNull(target) {
  try {
    return fs.realpathSync(target);
  } catch (error) {
    return null;
  }
}

// 경로 문자열만으로는 저장소 안팎을 가릴 수 없다. 심볼릭 링크는 저장소 안 경로로 밖의 파일을
// 가리킬 수 있으므로, 링크를 따라간 실제 경로까지 확인한 뒤에만 읽어 저장소 밖 내용이
// Claude 프롬프트와 GitLab 댓글로 새지 않게 한다.
function resolveReadablePath(repositoryRoot, relativePath) {
  const absolutePath = path.resolve(repositoryRoot, relativePath);
  if (!isInsideRepository(repositoryRoot, absolutePath)) {
    return { reason: 'outside the repository root' };
  }

  const realPath = realPathOrNull(absolutePath);
  if (!realPath) return { reason: 'deleted or unreadable' };

  // 저장소 루트 자체가 링크 아래 있을 수 있어(임시 디렉터리 등) 루트도 실제 경로로 비교한다.
  if (!isInsideRepository(realPathOrNull(repositoryRoot) ?? repositoryRoot, realPath)) {
    return { reason: 'symlink outside the repository root' };
  }

  return { realPath };
}

// 상한을 넘는 파일은 전부 읽어 잘라내는 대신 앞부분만 읽는다. 13MB 모델 가중치처럼 큰 파일도
// 버퍼가 상한을 넘지 않으므로 메모리 사용이 튀지 않는다.
function readHead(absolutePath, limit) {
  const handle = fs.openSync(absolutePath, 'r');
  try {
    const buffer = Buffer.alloc(limit);
    return buffer.subarray(0, fs.readSync(handle, buffer, 0, limit, 0));
  } finally {
    fs.closeSync(handle);
  }
}

function readFileEntry(repositoryRoot, relativePath, kind) {
  const resolved = resolveReadablePath(repositoryRoot, relativePath);
  if (resolved.reason) return { path: relativePath, reason: resolved.reason };

  let fileBytes;
  let buffer;
  try {
    fileBytes = fs.statSync(resolved.realPath).size;
    buffer = readHead(resolved.realPath, Math.min(fileBytes, MAX_FILE_BYTES));
  } catch (error) {
    return { path: relativePath, reason: 'deleted or unreadable' };
  }

  // NUL 바이트가 있으면 텍스트가 아니다. 이미지·모델 가중치 같은 파일은 첨부해도 검토에 쓸 수 없다.
  if (buffer.includes(0)) return { path: relativePath, reason: 'binary' };

  const entry = {
    path: relativePath,
    kind,
    content: buffer.toString('utf8'),
    bytes: buffer.byteLength,
  };
  return fileBytes > buffer.byteLength ? { ...entry, originalBytes: fileBytes } : entry;
}

function renderFiles(files) {
  return files.flatMap((file) => {
    const fence = fenceFor(file.content);
    const heading = file.originalBytes
      ? `### \`${file.path}\` (first ${file.bytes} of ${file.originalBytes} bytes; the rest is not attached)`
      : `### \`${file.path}\``;
    return ['', heading, fence, file.content, fence];
  });
}

function renderContext(files, skipped) {
  const changed = files.filter((file) => file.kind === 'changed');
  const candidates = files.filter((file) => file.kind === 'candidate');
  const lines = [
    '## Changed files',
    `Read-only content of every changed file: ${changed.length} files, ${changed.reduce((total, file) => total + file.bytes, 0)} bytes.`,
    'No file-count or total-size limit is applied. Review these files together with the diff.',
    `A file over ${MAX_FILE_BYTES} bytes keeps only its beginning, and its heading says so.`,
  ];

  if (changed.length === 0) lines.push('No readable changed file was available.');
  lines.push(...renderFiles(changed));

  lines.push(
    '',
    '## Reusable repository context',
    `Read-only candidate files: ${candidates.length}, ${candidates.reduce((total, file) => total + file.bytes, 0)} bytes.`,
    'These files are evidence for reuse/duplication suggestions. Do not infer that an unlisted component exists.',
  );

  if (candidates.length === 0) lines.push('No candidate file was found.');
  lines.push(...renderFiles(candidates));

  if (skipped.length > 0) {
    lines.push('', '## Skipped files');
    lines.push('These changed files were not attached, so do not assume their content.');
    for (const file of skipped) lines.push(`- \`${file.path}\`: ${file.reason}`);
  }

  return lines.join('\n');
}

function collectReviewContext(context, options = {}) {
  const changedPaths = [...new Set((context.changedFiles || []).map(normalizePath))];
  const candidates = candidatePaths(context, options.files);
  const requested = [
    ...changedPaths.map((file) => ({ path: file, kind: 'changed' })),
    ...candidates.map((file) => ({ path: file, kind: 'candidate' })),
  ];

  const files = [];
  const skipped = [];
  let totalBytes = 0;

  for (const request of requested) {
    const entry = readFileEntry(context.repositoryRoot, request.path, request.kind);
    if (entry.reason) {
      skipped.push(entry);
      continue;
    }
    files.push(entry);
    totalBytes += entry.bytes;
  }

  return {
    files,
    skipped,
    totalBytes,
    markdown: renderContext(files, skipped),
  };
}

module.exports = {
  candidatePaths,
  collectReviewContext,
  fenceFor,
  requestedComponentNames,
};
