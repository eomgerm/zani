const assert = require('node:assert/strict');
const test = require('node:test');

const { loadEnvFile } = require('../lib/env.cjs');

test('loadEnvFile reads unset values without overwriting existing process environment values', () => {
  const environment = { EXISTING_VALUE: 'from-process' };

  const loaded = loadEnvFile('C:/repo/.env', {
    env: environment,
    readFile: () => [
      '# local secrets',
      'GITLAB_TOKEN=local-token',
      'EXISTING_VALUE=from-file',
      'QUOTED_VALUE="hello world"',
    ].join('\n'),
  });

  assert.deepEqual(loaded, ['GITLAB_TOKEN', 'QUOTED_VALUE']);
  assert.equal(environment.GITLAB_TOKEN, 'local-token');
  assert.equal(environment.EXISTING_VALUE, 'from-process');
  assert.equal(environment.QUOTED_VALUE, 'hello world');
});

test('loadEnvFile ignores a missing .env file', () => {
  const environment = {};
  const loaded = loadEnvFile('C:/repo/.env', {
    env: environment,
    readFile: () => {
      const error = new Error('not found');
      error.code = 'ENOENT';
      throw error;
    },
  });

  assert.deepEqual(loaded, []);
  assert.deepEqual(environment, {});
});
