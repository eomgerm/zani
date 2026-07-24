const fs = require('node:fs');

function parseEnvValue(value) {
  const trimmed = value.trim();
  if (
    trimmed.length >= 2
    && ((trimmed.startsWith('"') && trimmed.endsWith('"')) || (trimmed.startsWith("'") && trimmed.endsWith("'")))
  ) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
}

function loadEnvFile(filePath, {
  env = process.env,
  readFile = fs.readFileSync,
} = {}) {
  let source;
  try {
    source = readFile(filePath, 'utf8');
  } catch (error) {
    if (error.code === 'ENOENT') return [];
    throw error;
  }

  const loaded = [];
  for (const line of String(source).split(/\r?\n/u)) {
    const match = line.match(/^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/u);
    if (!match) continue;

    const [, key, rawValue] = match;
    if (env[key] !== undefined) continue;
    env[key] = parseEnvValue(rawValue);
    loaded.push(key);
  }
  return loaded;
}

module.exports = { loadEnvFile, parseEnvValue };
