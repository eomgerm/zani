#!/usr/bin/env node

const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const { parseArgs } = require('../lib/args.cjs');
const { runClaudeReview } = require('../lib/claude.cjs');
const { checkDddRules } = require('../lib/ddd-rules.cjs');
const { loadEnvFile } = require('../lib/env.cjs');
const { checkGitConventions, collectGitContext, fetchMergeRequestHead } = require('../lib/git.cjs');
const { getMergeRequestMetadata, publishReport } = require('../lib/gitlab.cjs');
const { collectReviewContext } = require('../lib/repository-context.cjs');
const { buildReport } = require('../lib/report.cjs');

const HELP = `ZANI Claude Code MR reviewer

Usage:
  npm run review:mr -- [options]

Options:
  --base <ref>       비교 기준 브랜치 (기본값: origin/dev)
  --mr <url|iid>     최신 MR 코드 검토 및 GitLab 요약 댓글 게시
  --publish          이전 명령과의 호환용 옵션 (--mr 사용 시 자동 적용)
  --output <path>    JSON 결과 경로 (기본값: code-review/output/review.json)
  --help, -h         도움말 출력
`;

function writeJsonReport(output, report, repositoryRoot) {
  const outputPath = path.isAbsolute(output) ? output : path.join(repositoryRoot, output);
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  return outputPath;
}

function getOriginRemote(repositoryRoot) {
  return execFileSync('git', ['remote', 'get-url', 'origin'], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
}

function getRepositoryRoot() {
  return execFileSync('git', ['rev-parse', '--show-toplevel'], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
}

async function main(argv, adapters = {}) {
  const collectContext = adapters.collectContext || collectGitContext;
  const checkGit = adapters.checkGit || checkGitConventions;
  const checkDdd = adapters.checkDdd || checkDddRules;
  const loadEnvironment = adapters.loadEnv || loadEnvFile;
  const collectRepositoryContext = adapters.collectRepositoryContext || collectReviewContext;
  const reviewWithClaude = adapters.reviewWithClaude || runClaudeReview;
  const writeReport = adapters.writeReport || writeJsonReport;
  const getRemoteUrl = adapters.getRemoteUrl || getOriginRemote;
  const getRepository = adapters.getRepositoryRoot || getRepositoryRoot;
  const getMrMetadata = adapters.getMrMetadata || getMergeRequestMetadata;
  const fetchMrHead = adapters.fetchMrHead || fetchMergeRequestHead;
  const publish = adapters.publish || publishReport;
  const log = adapters.log || console.log;
  const error = adapters.error || console.error;

  try {
    const options = parseArgs(argv);
    if (options.help) {
      log(HELP);
      return 0;
    }

    const repositoryRoot = getRepository();
    loadEnvironment(path.join(repositoryRoot, '.env'));
    let remoteUrl;
    let contextOptions;
    if (options.mr) {
      remoteUrl = getRemoteUrl(repositoryRoot);
      const mrMetadata = await getMrMetadata({ mr: options.mr, remoteUrl });
      const headRef = fetchMrHead({
        iid: mrMetadata.iid,
        repositoryRoot,
        expectedSha: mrMetadata.sha,
      });
      contextOptions = {
        headRef,
        branch: mrMetadata.sourceBranch,
        includeWorkingTree: false,
      };
    }

    const context = collectContext(options.baseRef, contextOptions);
    let report;
    if (context.changedFiles.length === 0) {
      report = buildReport({
        baseRef: context.baseRef,
        headSha: context.headSha,
        summary: '기준 브랜치와 비교할 변경 파일이 없습니다.',
        findings: [],
        metadata: { branch: context.branch, changedFiles: [] },
      });
    } else {
      const gitFindings = checkGit(context);
      const dddFindings = checkDdd(context);
      const deterministicFindings = [...gitFindings, ...dddFindings];
      const repositoryContext = collectRepositoryContext(context);
      const claudeReview = await reviewWithClaude({ context, deterministicFindings, repositoryContext });
      report = buildReport({
        baseRef: context.baseRef,
        headSha: context.headSha,
        summary: claudeReview.summary,
        findings: [...deterministicFindings, ...claudeReview.findings],
        metadata: {
          branch: context.branch,
          changedFiles: context.changedFiles,
          commitMessages: context.commitMessages,
          deterministicFindingCount: deterministicFindings.length,
          claudeFindingCount: claudeReview.findings.length,
          repositoryContext: {
            files: repositoryContext.files?.map((file) => file.path) || [],
            totalBytes: repositoryContext.totalBytes || 0,
            skippedFiles: repositoryContext.skipped?.map((file) => `${file.path} (${file.reason})`) || [],
          },
        },
      });
    }

    const outputPath = writeReport(options.output, report, context.repositoryRoot);
    log(`리뷰 JSON 저장: ${outputPath || options.output}`);

    if (options.publish) {
      remoteUrl = remoteUrl || getRemoteUrl(context.repositoryRoot);
      await publish({
        mr: options.mr,
        remoteUrl,
        report,
      });
      log('GitLab MR 댓글 게시 완료');
    }

    log(report.passed ? '리뷰 통과' : 'blocker가 있어 병합을 차단합니다.');
    return report.passed ? 0 : 1;
  } catch (cause) {
    error(`리뷰 실행 실패: ${cause.message}`);
    return 1;
  }
}

if (require.main === module) {
  main(process.argv.slice(2)).then((exitCode) => {
    process.exitCode = exitCode;
  });
}

module.exports = { HELP, getOriginRemote, main, writeJsonReport };
