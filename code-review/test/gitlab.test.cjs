const assert = require('node:assert/strict');
const test = require('node:test');

const { discussionMarker, publishReport, resolveMrTarget } = require('../lib/gitlab.cjs');

const report = {
  passed: false,
  summary: 'DDD 위반',
  baseRef: 'origin/dev',
  headSha: 'head-sha',
  findings: [{
    severity: 'blocker',
    type: 'backend-ddd',
    message: 'Domain이 JPA를 import합니다.',
    file: 'backend/src/main/java/com/a105/zani/room/domain/model/Room.java',
    line: 4,
    suggestion: 'JPA Entity를 infrastructure로 이동하세요.',
  }],
  metadata: {},
};

const mrResponse = {
  id: 100,
  iid: 17,
  project_id: 55,
  title: 'review agent',
  state: 'opened',
  sha: 'head-sha',
  web_url: 'https://lab.ssafy.com/group/project/-/merge_requests/17',
  diff_refs: {
    base_sha: 'base-sha',
    start_sha: 'start-sha',
    head_sha: 'head-sha',
  },
};

function response(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    async json() { return body; },
    async text() { return typeof body === 'string' ? body : JSON.stringify(body); },
  };
}

test('resolveMrTarget accepts a full MR URL and a numeric IID', () => {
  assert.deepEqual(
    resolveMrTarget(
      'https://lab.ssafy.com/group/project/-/merge_requests/17',
      'https://unused.example/unused.git',
    ),
    {
      apiBase: 'https://lab.ssafy.com/api/v4',
      projectPath: 'group/project',
      projectId: 'group%2Fproject',
      iid: '17',
    },
  );

  assert.deepEqual(
    resolveMrTarget('17', 'git@lab.ssafy.com:group/project.git'),
    {
      apiBase: 'https://lab.ssafy.com/api/v4',
      projectPath: 'group/project',
      projectId: 'group%2Fproject',
      iid: '17',
    },
  );
});

test('publishReport refuses to publish a stale reviewed SHA', async () => {
  await assert.rejects(
    () => publishReport({
      mr: '17',
      remoteUrl: 'https://lab.ssafy.com/group/project.git',
      token: 'secret',
      report: { ...report, headSha: 'old-sha' },
    }, {
      fetchImpl: async () => response(mrResponse),
    }),
    /재검토/,
  );
});

test('publishReport posts a summary note and positioned discussions', async () => {
  const requests = [];
  const result = await publishReport({
    mr: '17',
    remoteUrl: 'https://lab.ssafy.com/group/project.git',
    token: 'secret',
    report,
  }, {
    async fetchImpl(url, options = {}) {
      requests.push({ url, options });
      if (url.endsWith('/notes?per_page=100') || url.endsWith('/discussions?per_page=100')) return response([]);
      if (!options.method || options.method === 'GET') return response(mrResponse);
      return response({ id: requests.length, body: JSON.parse(options.body).body }, 201);
    },
  });

  assert.equal(result.summaryPosted, true);
  assert.equal(result.discussionsPosted, 1);
  assert.equal(requests[0].options.headers['PRIVATE-TOKEN'], 'secret');
  assert.match(requests[1].url, /\/notes\?per_page=100$/);
  assert.match(requests[2].url, /\/discussions\?per_page=100$/);
  assert.match(requests[3].url, /\/discussions$/);
  assert.match(requests[4].url, /\/notes$/);

  const discussionBody = JSON.parse(requests[3].options.body);
  assert.equal(discussionBody.position.new_line, 4);
  assert.equal(discussionBody.position.head_sha, 'head-sha');
  assert.equal(
    discussionBody.position.new_path,
    'backend/src/main/java/com/a105/zani/room/domain/model/Room.java',
  );
});

test('publishReport skips a commit that already has this reviewer comment', async () => {
  const requests = [];
  const result = await publishReport({
    mr: '17',
    remoteUrl: 'https://lab.ssafy.com/group/project.git',
    token: 'secret',
    report,
  }, {
    async fetchImpl(url, options = {}) {
      requests.push({ url, options });
      if (url.endsWith('/notes?per_page=100')) {
        return response([{ body: '<!-- zani-code-review:head-sha -->' }]);
      }
      return response(mrResponse);
    },
  });

  assert.equal(result.skipped, true);
  assert.equal(result.summaryPosted, false);
  assert.equal(requests.length, 2);
});

test('publishReport does not leave the idempotency marker when an inline discussion fails', async () => {
  const requests = [];
  await assert.rejects(
    () => publishReport({
      mr: '17',
      remoteUrl: 'https://lab.ssafy.com/group/project.git',
      token: 'secret',
      report,
    }, {
      async fetchImpl(url, options = {}) {
        requests.push({ url, options });
        if (url.endsWith('/notes?per_page=100')) return response([]);
        if (url.endsWith('/discussions')) return response('discussion failed', 500);
        return response(mrResponse);
      },
    }),
    /discussion failed/,
  );

  assert.ok(!requests.some((request) => request.url.endsWith('/notes') && request.options.method === 'POST'));
});

test('publishReport does not duplicate an inline discussion during a partial-run retry', async () => {
  const requests = [];
  const marker = discussionMarker(report, report.findings[0]);
  const result = await publishReport({
    mr: '17',
    remoteUrl: 'https://lab.ssafy.com/group/project.git',
    token: 'secret',
    report,
  }, {
    async fetchImpl(url, options = {}) {
      requests.push({ url, options });
      if (url.endsWith('/notes?per_page=100')) return response([]);
      if (url.endsWith('/discussions?per_page=100')) {
        return response([{ notes: [{ body: `existing comment\n${marker}` }] }]);
      }
      if (!options.method || options.method === 'GET') return response(mrResponse);
      return response({ id: requests.length }, 201);
    },
  });

  assert.equal(result.discussionsPosted, 0);
  assert.ok(!requests.some((request) => request.url.endsWith('/discussions') && request.options.method === 'POST'));
  assert.ok(requests.some((request) => request.url.endsWith('/notes') && request.options.method === 'POST'));
});

test('publishReport skips draft and closed merge requests', async () => {
  for (const mrData of [
    { ...mrResponse, draft: true },
    { ...mrResponse, state: 'closed' },
  ]) {
    const requests = [];
    const result = await publishReport({
      mr: '17',
      remoteUrl: 'https://lab.ssafy.com/group/project.git',
      token: 'secret',
      report,
    }, {
      async fetchImpl(url, options = {}) {
        requests.push({ url, options });
        return response(mrData);
      },
    });

    assert.equal(result.skipped, true);
    assert.equal(result.reason, 'MR is draft or not opened');
    assert.equal(requests.length, 1);
  }
});

test('publishReport requires a token and reports GitLab API failures', async () => {
  await assert.rejects(
    () => publishReport({
      mr: '17', remoteUrl: 'https://lab.ssafy.com/group/project.git', report,
    }),
    /GITLAB_TOKEN/,
  );

  await assert.rejects(
    () => publishReport({
      mr: '17',
      remoteUrl: 'https://lab.ssafy.com/group/project.git',
      token: 'secret',
      report,
    }, {
      fetchImpl: async () => response('forbidden', 403),
    }),
    /403.*forbidden/,
  );
});
