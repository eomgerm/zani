const assert = require('node:assert/strict');
const test = require('node:test');

const { parseArgs } = require('../lib/args.cjs');

test('parseArgs applies local review defaults', () => {
  assert.deepEqual(parseArgs([]), {
    baseRef: 'origin/dev',
    mr: null,
    publish: false,
    output: 'code-review/output/review.json',
    help: false,
  });
});

test('parseArgs reads every supported option', () => {
  assert.deepEqual(
    parseArgs([
      '--base', 'upstream/dev',
      '--mr', 'https://lab.ssafy.com/group/project/-/merge_requests/17',
      '--publish',
      '--output', 'tmp/result.json',
    ]),
    {
      baseRef: 'upstream/dev',
      mr: 'https://lab.ssafy.com/group/project/-/merge_requests/17',
      publish: true,
      output: 'tmp/result.json',
      help: false,
    },
  );
});

test('parseArgs publishes automatically when an MR is specified', () => {
  const options = parseArgs(['--mr', '13']);

  assert.equal(options.mr, '13');
  assert.equal(options.publish, true);
});

test('parseArgs requires an MR target when publishing', () => {
  assert.throws(() => parseArgs(['--publish']), /--mr/);
});

test('parseArgs rejects unknown and missing option values', () => {
  assert.throws(() => parseArgs(['--wat']), /알 수 없는 옵션/);
  assert.throws(() => parseArgs(['--base']), /값이 필요/);
});
