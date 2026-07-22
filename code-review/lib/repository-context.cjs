const fs = require('node:fs');
const path = require('node:path');

const DEFAULT_LIMITS = Object.freeze({
  maxFiles: 12,
  maxBytes: 120 * 1024,
  maxFileBytes: 32 * 1024,
});

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

function renderContext(files, limits, totalBytes, truncated) {
  const lines = [
    '## Reusable repository context',
    `Read-only candidate files: ${files.length}/${limits.maxFiles}, ${totalBytes}/${limits.maxBytes} bytes.`,
    'These files are evidence for reuse/duplication suggestions. Do not infer that an unlisted component exists.',
  ];
  if (truncated) lines.push('The candidate list was truncated by the configured safety limit.');

  for (const file of files) {
    lines.push('', `### \`${file.path}\``, '```', file.content, '```');
  }
  return lines.join('\n');
}

function collectReviewContext(context, options = {}) {
  const limits = { ...DEFAULT_LIMITS, ...options };
  const candidates = candidatePaths(context, options.files);
  const selected = [];
  let totalBytes = 0;
  let truncated = false;

  for (const candidate of candidates) {
    if (selected.length >= limits.maxFiles) {
      truncated = true;
      break;
    }

    const absolutePath = path.resolve(context.repositoryRoot, candidate);
    if (!isInsideRepository(context.repositoryRoot, absolutePath)) {
      truncated = true;
      continue;
    }

    const content = fs.readFileSync(absolutePath, 'utf8');
    const bytes = Buffer.byteLength(content, 'utf8');
    if (bytes > limits.maxFileBytes || totalBytes + bytes > limits.maxBytes) {
      truncated = true;
      continue;
    }

    selected.push({ path: candidate, content, bytes });
    totalBytes += bytes;
  }

  return {
    files: selected,
    totalBytes,
    truncated,
    markdown: renderContext(selected, limits, totalBytes, truncated),
  };
}

module.exports = {
  DEFAULT_LIMITS,
  candidatePaths,
  collectReviewContext,
  requestedComponentNames,
};
