const { renderMarkdown } = require('./report.cjs');
const { createHash } = require('node:crypto');

function remoteProject(remoteUrl) {
  const scpStyle = remoteUrl.match(/^git@([^:]+):(.+)$/u);
  if (scpStyle) {
    return {
      origin: `https://${scpStyle[1]}`,
      projectPath: scpStyle[2].replace(/\.git$/u, '').replace(/^\//u, ''),
    };
  }

  let parsed;
  try {
    parsed = new URL(remoteUrl);
  } catch (error) {
    throw new Error(`Git remote URL을 해석할 수 없습니다: ${remoteUrl}`);
  }
  return {
    origin: `https://${parsed.host}`,
    projectPath: decodeURIComponent(parsed.pathname.replace(/^\//u, '').replace(/\.git$/u, '')),
  };
}

function resolveMrTarget(mr, remoteUrl) {
  if (!mr) throw new Error('GitLab MR URL 또는 IID가 필요합니다.');

  let origin;
  let projectPath;
  let iid;
  if (/^https?:\/\//u.test(String(mr))) {
    const parsed = new URL(String(mr));
    const match = parsed.pathname.match(/^\/(.+)\/-\/merge_requests\/(\d+)\/?$/u);
    if (!match) throw new Error(`GitLab MR URL 형식이 올바르지 않습니다: ${mr}`);
    origin = parsed.origin;
    projectPath = decodeURIComponent(match[1]).replace(/\.git$/u, '');
    iid = match[2];
  } else {
    if (!/^\d+$/u.test(String(mr))) throw new Error(`MR IID는 숫자여야 합니다: ${mr}`);
    ({ origin, projectPath } = remoteProject(remoteUrl));
    iid = String(mr);
  }

  return {
    apiBase: `${origin}/api/v4`,
    projectPath,
    projectId: encodeURIComponent(projectPath),
    iid,
  };
}

async function requestJson(fetchImpl, url, { token, method = 'GET', body } = {}) {
  const options = {
    method,
    headers: {
      'PRIVATE-TOKEN': token,
      'Content-Type': 'application/json',
    },
  };
  if (body !== undefined) options.body = JSON.stringify(body);

  const response = await fetchImpl(url, options);
  if (!response.ok) {
    const detail = await response.text();
    throw new Error(`GitLab API 요청 실패 (${response.status}): ${detail}`);
  }
  return response.json();
}

function discussionBody(finding, marker) {
  const lines = [
    `**${finding.severity.toUpperCase()} / ${finding.type}**`,
    '',
    finding.message,
  ];
  if (finding.suggestion) lines.push('', `제안: ${finding.suggestion}`);
  lines.push('', marker);
  return lines.join('\n');
}

function reviewMarker(report) {
  return `<!-- zani-code-review:${report.headSha} -->`;
}

function discussionMarker(report, finding) {
  const identifier = [
    report.headSha,
    finding.file || '',
    finding.line || '',
    finding.type || '',
    finding.message || '',
  ].join('\n');
  const hash = createHash('sha256').update(identifier).digest('hex').slice(0, 16);
  return `<!-- zani-code-review-discussion:${hash} -->`;
}

function discussionBodies(discussions) {
  if (!Array.isArray(discussions)) return [];
  return discussions.flatMap((discussion) => (discussion.notes || []).map((note) => String(note.body || '')));
}

async function publishReport({
  mr,
  remoteUrl,
  token = process.env.GITLAB_TOKEN,
  report,
}, {
  fetchImpl = globalThis.fetch,
  render = renderMarkdown,
} = {}) {
  if (!token) throw new Error('MR 댓글 게시에는 GITLAB_TOKEN 환경 변수가 필요합니다.');
  if (typeof fetchImpl !== 'function') throw new Error('GitLab API를 호출할 fetch 구현을 찾을 수 없습니다.');

  const target = resolveMrTarget(mr, remoteUrl);
  const mrBaseUrl = `${target.apiBase}/projects/${target.projectId}/merge_requests/${target.iid}`;
  const mrData = await requestJson(fetchImpl, mrBaseUrl, { token });

  if (mrData.state !== 'opened' || mrData.draft || mrData.work_in_progress) {
    return {
      skipped: true,
      reason: 'MR is draft or not opened',
      summaryPosted: false,
      discussionsPosted: 0,
      webUrl: mrData.web_url,
    };
  }

  if (mrData.sha !== report.headSha) {
    throw new Error(
      `MR SHA(${mrData.sha})와 검토 SHA(${report.headSha})가 달라 재검토가 필요합니다.`,
    );
  }

  const marker = reviewMarker(report);
  const notes = await requestJson(fetchImpl, `${mrBaseUrl}/notes?per_page=100`, { token });
  if (Array.isArray(notes) && notes.some((note) => String(note.body || '').includes(marker))) {
    return {
      skipped: true,
      summaryPosted: false,
      discussionsPosted: 0,
      webUrl: mrData.web_url,
    };
  }

  const postedDiscussionBodies = discussionBodies(await requestJson(
    fetchImpl,
    `${mrBaseUrl}/discussions?per_page=100`,
    { token },
  ));

  let discussionsPosted = 0;
  for (const finding of report.findings) {
    if (!finding.file || !finding.line) continue;
    const findingMarker = discussionMarker(report, finding);
    if (postedDiscussionBodies.some((body) => body.includes(findingMarker))) continue;
    await requestJson(fetchImpl, `${mrBaseUrl}/discussions`, {
      token,
      method: 'POST',
      body: {
        body: discussionBody(finding, findingMarker),
        position: {
          position_type: 'text',
          base_sha: mrData.diff_refs.base_sha,
          start_sha: mrData.diff_refs.start_sha,
          head_sha: mrData.diff_refs.head_sha,
          new_path: finding.file,
          new_line: finding.line,
        },
      },
    });
    discussionsPosted += 1;
  }

  await requestJson(fetchImpl, `${mrBaseUrl}/notes`, {
    token,
    method: 'POST',
    body: { body: `${render(report)}\n\n${marker}` },
  });

  return {
    skipped: false,
    summaryPosted: true,
    discussionsPosted,
    webUrl: mrData.web_url,
  };
}

module.exports = {
  discussionMarker,
  publishReport,
  remoteProject,
  requestJson,
  resolveMrTarget,
  reviewMarker,
};
