#!/usr/bin/env node

const { spawnSync } = require('child_process');
const path = require('path');

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd,
    encoding: options.encoding,
    env: options.env,
    stdio: options.stdio,
  });

  if (result.error) {
    throw result.error;
  }

  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }

  return result.stdout ?? '';
}

function git(repoRoot, args) {
  return run('git', args, { cwd: repoRoot, encoding: 'utf8' });
}

function parseNullSeparated(value) {
  return value.split('\0').filter(Boolean);
}

function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function absolutePathRegex(filePath) {
  return `^${path.resolve(filePath).split(/[\\/]/).map(escapeRegex).join('[\\\\/]')}$`;
}

function runSpotless(backendDir, spotlessFiles) {
  const env = {
    ...process.env,
    ORG_GRADLE_PROJECT_spotlessFiles: spotlessFiles,
  };

  if (process.platform === 'win32') {
    const gradlewBat = path.join(backendDir, 'gradlew.bat');
    run('cmd.exe', ['/d', '/s', '/c', gradlewBat, 'spotlessApply', 'spotlessCheck'], {
      cwd: backendDir,
      env,
      stdio: 'inherit',
    });
    return;
  }

  run('sh', ['./gradlew', 'spotlessApply', 'spotlessCheck'], {
    cwd: backendDir,
    env,
    stdio: 'inherit',
  });
}

const repoRoot = git(process.cwd(), ['rev-parse', '--show-toplevel']).trim();
const backendDir = path.join(repoRoot, 'backend');
const javaSourcePaths = ['backend/src/main/java', 'backend/src/test/java'];

const stagedJavaFiles = parseNullSeparated(
  git(repoRoot, [
    'diff',
    '--cached',
    '--name-only',
    '--diff-filter=ACMR',
    '-z',
    '--',
    ...javaSourcePaths,
  ])
).filter((file) => file.endsWith('.java'));

if (stagedJavaFiles.length === 0) {
  process.exit(0);
}

const unstagedFiles = new Set(
  parseNullSeparated(git(repoRoot, ['diff', '--name-only', '-z', '--', ...stagedJavaFiles]))
);
const partiallyStagedJavaFiles = stagedJavaFiles.filter((file) => unstagedFiles.has(file));

if (partiallyStagedJavaFiles.length > 0) {
  console.error('');
  console.error('❌ 부분 스테이징된 Java 파일은 자동 포맷할 수 없습니다.');
  console.error('   아래 파일을 전부 스테이징하거나 스테이징을 해제한 뒤 다시 커밋하세요.');
  for (const file of partiallyStagedJavaFiles) {
    console.error(`   - ${file}`);
  }
  console.error('');
  process.exit(1);
}

if (stagedJavaFiles.some((file) => file.includes(','))) {
  console.error('❌ 파일명에 쉼표가 포함된 Java 파일은 Spotless 선택 포맷을 지원하지 않습니다.');
  process.exit(1);
}

const spotlessFiles = stagedJavaFiles
  .map((file) => absolutePathRegex(path.join(repoRoot, file)))
  .join(',');

console.log(`🧹 스테이징된 Java 파일 ${stagedJavaFiles.length}개를 Spotless로 포맷합니다.`);
runSpotless(backendDir, spotlessFiles);
run('git', ['add', '--', ...stagedJavaFiles], { cwd: repoRoot, stdio: 'inherit' });
console.log('✅ 포맷 결과를 스테이징하고 Spotless 검사를 통과했습니다.');
