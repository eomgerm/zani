const assert = require('node:assert/strict');
const test = require('node:test');

const { getMergeRequestMetadata, publishReport, resolveMrTarget } = require('../lib/gitlab.cjs');

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

test('getMergeRequestMetadata returns the source branch and latest SHA', async () => {
  const metadata = await getMergeRequestMetadata({
    mr: '17',
    remoteUrl: 'https://lab.ssafy.com/group/project.git',
    token: 'secret',
  }, {
    fetchImpl: async () => response({
      ...mrResponse,
      source_branch: 'be/feat/example-S15P11A105-17',
    }),
  });

  assert.deepEqual(metadata, {
    iid: '17',
    sourceBranch: 'be/feat/example-S15P11A105-17',
    sha: 'head-sha',
  });
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

test('publishReport posts one summary note without inline discussions', async () => {
  const requests = [];
  const result = await publishReport({
    mr: '17',
    remoteUrl: 'https://lab.ssafy.com/group/project.git',
    token: 'secret',
    report,
  }, {
    async fetchImpl(url, options = {}) {
      requests.push({ url, options });
      if (url.endsWith('/notes?per_page=100')) return response([]);
      if (url.endsWith('/discussions')) return response('inline discussions are disabled', 400);
      if (!options.method || options.method === 'GET') return response(mrResponse);
      return response({ id: requests.length, body: JSON.parse(options.body).body }, 201);
    },
  });

  assert.equal(result.summaryPosted, true);
  assert.equal(result.discussionsPosted, 0);
  assert.equal(requests[0].options.headers['PRIVATE-TOKEN'], 'secret');
  assert.match(requests[1].url, /\/notes\?per_page=100$/);
  assert.match(requests[2].url, /\/notes$/);
  assert.equal(requests.filter((request) => request.url.includes('/discussions')).length, 0);

  const noteBody = JSON.parse(requests[2].options.body).body;
  assert.match(noteBody, /Room\.java:4/);
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
