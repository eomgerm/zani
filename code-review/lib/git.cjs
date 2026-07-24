const { execFileSync, spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

function defaultRunGit(args, { cwd = process.cwd() } = {}) {
  return execFileSync('git', args, {
    cwd,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

function defaultRunScript(scriptPath, args = [], { cwd = process.cwd() } = {}) {
  const result = spawnSync(process.execPath, [scriptPath, ...args], {
    cwd,
    encoding: 'utf8',
    windowsHide: true,
  });
  return {
    status: result.status ?? 1,
    stdout: result.stdout || '',
    stderr: result.error?.message || result.stderr || '',
  };
}

function nonEmptyLines(value) {
  return value
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter(Boolean);
}

function untrackedDiff(repositoryRoot, files, readFile) {
  return files.map((file) => {
    const absolutePath = path.join(repositoryRoot, file).replaceAll('\\', '/');
    const content = readFile(absolutePath, 'utf8');
    const addedLines = content.split(/\r?\n/u).map((line) => `+${line}`).join('\n');
    return [
      `diff --git a/${file} b/${file}`,
      `new file: ${file}`,
      '--- /dev/null',
      `+++ b/${file}`,
      '@@ new file @@',
      addedLines,
    ].join('\n');
  }).join('\n\n');
}

function collectGitContext(baseRef = 'origin/dev', {
  headRef = 'HEAD',
  branch,
  includeWorkingTree = headRef === 'HEAD',
  runGit = defaultRunGit,
  readFile = fs.readFileSync,
  cwd = process.cwd(),
} = {}) {
  const repositoryRoot = runGit(['rev-parse', '--show-toplevel'], { cwd }).trim();
  runGit(['rev-parse', '--verify', baseRef], { cwd: repositoryRoot });
  runGit(['rev-parse', '--verify', headRef], { cwd: repositoryRoot });

  const committedFiles = nonEmptyLines(
    runGit(['diff', '--name-only', `${baseRef}...${headRef}`], { cwd: repositoryRoot }),
  );
  const workingFiles = includeWorkingTree ? nonEmptyLines(
    runGit(['diff', '--name-only', 'HEAD', '--'], { cwd: repositoryRoot }),
  ) : [];
  const untrackedFiles = includeWorkingTree ? nonEmptyLines(
    runGit(['ls-files', '--others', '--exclude-standard'], { cwd: repositoryRoot }),
  ) : [];
  const changedFiles = [...new Set([...committedFiles, ...workingFiles, ...untrackedFiles])]
    .map((file) => file.replaceAll('\\', '/'));
  const diffParts = [
    runGit(['diff', '--unified=80', `${baseRef}...${headRef}`, '--'], { cwd: repositoryRoot }),
    includeWorkingTree ? runGit(['diff', '--unified=80', 'HEAD', '--'], { cwd: repositoryRoot }) : '',
    includeWorkingTree ? untrackedDiff(repositoryRoot, untrackedFiles, readFile) : '',
  ].filter((part) => part.trim());

  return {
    repositoryRoot,
    baseRef,
    branch: branch || runGit(['symbolic-ref', '--short', 'HEAD'], { cwd: repositoryRoot }).trim(),
    headSha: runGit(['rev-parse', headRef], { cwd: repositoryRoot }).trim(),
    changedFiles,
    diff: diffParts.join('\n\n'),
    commitMessages: nonEmptyLines(
      runGit(['log', '--format=%s', `${baseRef}..${headRef}`], { cwd: repositoryRoot }),
    ),
  };
}

function fetchMergeRequestHead({
  iid,
  repositoryRoot,
  expectedSha,
}, {
  runGit = defaultRunGit,
} = {}) {
  const headRef = `refs/zani-review/mr/${iid}`;
  runGit([
    'fetch', '--no-tags', 'origin', `refs/merge-requests/${iid}/head:${headRef}`,
  ], { cwd: repositoryRoot });
  const headSha = runGit(['rev-parse', headRef], { cwd: repositoryRoot }).trim();
  if (expectedSha && headSha !== expectedSha) {
    throw new Error(`MR 최신 커밋(${expectedSha})과 가져온 커밋(${headSha})이 다릅니다.`);
  }
  return headRef;
}

function scriptFailureMessage(result, fallback) {
  return String(result.stderr || result.stdout || fallback).trim();
}

function conventionFinding(message) {
  return {
    severity: 'blocker',
    type: 'git-convention',
    message,
  };
}

function checkGitConventions(context, { runScript = defaultRunScript } = {}) {
  const findings = [];
  const branchScript = path.join(context.repositoryRoot, 'scripts', 'validate-branch-name.cjs');
  const commitScript = path.join(context.repositoryRoot, 'scripts', 'verify-commit-msg.cjs');
  const branchResult = runScript(branchScript, [context.branch], { cwd: context.repositoryRoot });

  if (branchResult.status !== 0) {
    findings.push(conventionFinding(
      scriptFailureMessage(branchResult, `브랜치명 규칙 위반: ${context.branch}`),
    ));
  }

  const tempDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'zani-review-commit-'));
  try {
    context.commitMessages.forEach((message, index) => {
      const messageFile = path.join(tempDirectory, `commit-${index}.txt`);
      fs.writeFileSync(messageFile, `${message}\n`, 'utf8');
      const result = runScript(commitScript, [messageFile], { cwd: context.repositoryRoot });
      if (result.status !== 0) {
        findings.push(conventionFinding(
          scriptFailureMessage(result, `커밋 메시지 규칙 위반: ${message}`),
        ));
      }
    });
  } finally {
    fs.rmSync(tempDirectory, { recursive: true, force: true });
  }

  return findings;
}

module.exports = {
  checkGitConventions,
  collectGitContext,
  defaultRunGit,
  defaultRunScript,
  fetchMergeRequestHead,
};
