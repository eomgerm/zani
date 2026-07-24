const DEFAULTS = Object.freeze({
  baseRef: 'origin/dev',
  mr: null,
  publish: false,
  output: 'code-review/output/review.json',
  help: false,
});

function optionValue(argv, index, option) {
  const value = argv[index + 1];
  if (!value || value.startsWith('--')) {
    throw new Error(`${option} 옵션에는 값이 필요합니다.`);
  }
  return value;
}

function parseArgs(argv) {
  const options = { ...DEFAULTS };

  for (let index = 0; index < argv.length; index += 1) {
    const option = argv[index];

    if (option === '--help' || option === '-h') {
      options.help = true;
      continue;
    }
    if (option === '--publish') {
      options.publish = true;
      continue;
    }
    if (option === '--base' || option === '--mr' || option === '--output') {
      const value = optionValue(argv, index, option);
      index += 1;
      if (option === '--base') options.baseRef = value;
      if (option === '--mr') options.mr = value;
      if (option === '--output') options.output = value;
      continue;
    }

    throw new Error(`알 수 없는 옵션입니다: ${option}`);
  }

  if (options.mr) options.publish = true;

  if (options.publish && !options.mr) {
    throw new Error('--publish 사용 시 --mr <URL 또는 IID>가 필요합니다.');
  }

  return options;
}

module.exports = { DEFAULTS, parseArgs };
