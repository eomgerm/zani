const fs = require('node:fs');
const path = require('node:path');

const DEFAULT_CONFIG_PATH = path.join(__dirname, '..', 'config', 'ddd-rules.json');

function loadRules(configPath = DEFAULT_CONFIG_PATH) {
  return JSON.parse(fs.readFileSync(configPath, 'utf8'));
}

function lineNumber(source, offset) {
  return source.slice(0, offset).split('\n').length;
}

function blocker(type, message, file, line, suggestion) {
  const finding = { severity: 'blocker', type, message, file, line };
  if (suggestion) finding.suggestion = suggestion;
  return finding;
}

function eachMatch(source, pattern, callback) {
  for (const match of source.matchAll(pattern)) callback(match, lineNumber(source, match.index));
}

function escapePattern(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
}

function checkFrontend(file, source, rules) {
  const findings = [];
  const normalizedFile = file.replaceAll('\\', '/');
  const domainMatch = normalizedFile.match(/^fe\/src\/domains\/([^/]+)\/(domain|application|infrastructure|presentation)\//u);

  if (domainMatch) {
    const [, , layer] = domainMatch;

    if (layer === 'domain') {
      for (const moduleName of rules.domainForbiddenModules) {
        const pattern = new RegExp(
          `(?:from\\s+|require\\()(['"])${escapePattern(moduleName)}(?:/[^'"]*)?\\1`,
          'gu',
        );
        eachMatch(source, pattern, (match, line) => findings.push(blocker(
          'frontend-ddd',
          `domain 계층은 ${moduleName}에 의존할 수 없습니다.`,
          normalizedFile,
          line,
          '프레임워크 사용을 presentation 또는 infrastructure 계층으로 이동하세요.',
        )));
      }
      for (const globalName of rules.domainForbiddenGlobals) {
        const pattern = new RegExp(`\\b${escapePattern(globalName)}\\b`, 'gu');
        eachMatch(source, pattern, (match, line) => findings.push(blocker(
          'frontend-ddd',
          `domain 계층은 브라우저 API ${globalName}를 직접 사용할 수 없습니다.`,
          normalizedFile,
          line,
        )));
      }
    }

    const forbiddenLayers = layer === 'domain'
      ? ['presentation', 'application', 'infrastructure']
      : layer === 'application'
        ? ['presentation', 'infrastructure']
      : layer === 'infrastructure'
        ? ['presentation', 'application']
        : [];
    for (const forbiddenLayer of forbiddenLayers) {
      const pattern = new RegExp(
        `(?:from\\s+|require\\()(['"])[^'"]*(?:^|/)${forbiddenLayer}(?:/[^'"]*)?\\1`,
        'gmu',
      );
      eachMatch(source, pattern, (match, line) => findings.push(blocker(
        'frontend-ddd',
        `${layer} 계층은 ${forbiddenLayer} 계층에 의존할 수 없습니다.`,
        normalizedFile,
        line,
      )));
    }
  }

  const deepDomainImport = /(?:from\s+|require\()(['"])(?:@\/|~\/)?domains\/([^/'"]+)\/([^'"]+)\1/gu;
  eachMatch(source, deepDomainImport, (match, line) => {
    const currentDomain = domainMatch?.[1];
    const importedSubpath = match[3];
    if (currentDomain === match[2] || /^index(?:\.[cm]?[jt]sx?)?$/u.test(importedSubpath)) return;
    findings.push(blocker(
      'frontend-ddd',
      `도메인 외부에서는 ${match[2]} 도메인의 내부 경로가 아니라 index.ts 공개 API를 사용해야 합니다.`,
      normalizedFile,
      line,
    ));
  });

  return findings;
}

function backendPathInfo(file, mainRoot) {
  const normalizedFile = file.replaceAll('\\', '/');
  if (!normalizedFile.startsWith(`${mainRoot}/`) || !normalizedFile.endsWith('.java')) return null;
  const relative = normalizedFile.slice(mainRoot.length + 1);
  const segments = relative.split('/');
  if (segments.length < 2) return { file: normalizedFile, rootClass: segments[0] };
  return {
    file: normalizedFile,
    domain: segments[0],
    layer: segments[1],
    packageSegments: segments.slice(2, -1),
  };
}

function checkBackend(file, source, rules) {
  const info = backendPathInfo(file, rules.mainRoot);
  if (!info || info.rootClass) return [];

  const findings = [];
  const { domain, layer, packageSegments } = info;
  if (domain !== 'common' && !rules.layers.includes(layer)) {
    findings.push(blocker(
      'backend-ddd',
      `도메인 최상위 패키지는 presentation/application/domain/infrastructure 중 하나여야 합니다: ${layer}`,
      info.file,
      1,
    ));
    return findings;
  }

  if (layer === 'application' && rules.applicationTechnicalPackages.includes(packageSegments[0])) {
    findings.push(blocker(
      'backend-ddd',
      `application 패키지는 ${packageSegments[0]} 같은 기술 분류가 아니라 업무 유스케이스 이름을 사용해야 합니다.`,
      info.file,
      1,
    ));
  }

  const importPattern = /^import\s+([\w.]+);/gmu;
  eachMatch(source, importPattern, (match, line) => {
    const imported = match[1];
    const internalMatch = imported.match(/^com\.a105\.zani\.([^.]+)\.([^.]+)/u);
    const importedDomain = internalMatch?.[1];
    const importedLayer = internalMatch?.[2];

    // 완화(S15P11A105-175): infrastructure 계층끼리는 ERD FK 연관(@ManyToOne 등)을 위해
    // 다른 도메인의 infrastructure(JPA 엔티티 등) 참조를 허용한다.
    // application/domain/presentation 계층의 타 도메인 infrastructure 의존은 계속 차단한다.
    if (domain !== 'common'
        && layer !== 'infrastructure'
        && importedDomain
        && importedDomain !== domain
        && importedLayer === 'infrastructure') {
      findings.push(blocker(
        'backend-ddd',
        `다른 도메인(${importedDomain})의 infrastructure에 직접 의존할 수 없습니다.`,
        info.file,
        line,
      ));
      return;
    }

    if (layer === 'domain') {
      const forbiddenExternal = rules.domainForbiddenImports.some(
        (prefix) => imported === prefix || imported.startsWith(`${prefix}.`),
      );
      const forbiddenInternal = importedDomain === domain
        && ['presentation', 'application', 'infrastructure'].includes(importedLayer);
      if (forbiddenExternal || forbiddenInternal) {
        findings.push(blocker(
          'backend-ddd',
          `domain 계층은 외부 프레임워크나 바깥 계층을 import할 수 없습니다: ${imported}`,
          info.file,
          line,
        ));
      }
    }

    if (layer === 'application') {
      const forbiddenExternal = rules.applicationForbiddenImports.some(
        (prefix) => imported === prefix || imported.startsWith(`${prefix}.`),
      );
      const forbiddenInternal = importedDomain === domain && importedLayer === 'infrastructure';
      if (forbiddenExternal || forbiddenInternal) {
        findings.push(blocker(
          'backend-ddd',
          `application 계층은 infrastructure/JPA/Web 구현에 의존할 수 없습니다: ${imported}`,
          info.file,
          line,
        ));
      }
    }

    if (layer === 'presentation' && importedLayer === 'infrastructure') {
      findings.push(blocker(
        'backend-ddd',
        `presentation 계층은 infrastructure에 직접 의존할 수 없습니다: ${imported}`,
        info.file,
        line,
      ));
    }
  });

  eachMatch(source, /@(?:jakarta\.persistence\.)?Entity\b/gu, (match, line) => {
    const validEntityPath = layer === 'infrastructure'
      && packageSegments[0] === 'persistence'
      && packageSegments[1] === 'entity';
    if (!validEntityPath) {
      findings.push(blocker(
        'backend-ddd',
        'JPA Entity는 infrastructure/persistence/entity에 두고 Domain Model과 분리해야 합니다.',
        info.file,
        line,
      ));
    }
  });

  return findings;
}

function checkDddRules(context, {
  readFile = (file) => fs.readFileSync(path.join(context.repositoryRoot, file), 'utf8'),
  fileExists = (file) => fs.existsSync(path.join(context.repositoryRoot, file)),
  config = loadRules(),
} = {}) {
  const findings = [];
  for (const file of context.changedFiles) {
    if (!/\.(?:[cm]?[jt]sx?|java)$/u.test(file)) continue;
    if (!fileExists(file)) continue;
    const source = readFile(file);
    if (file.replaceAll('\\', '/').startsWith(`${config.frontend.root}/`)) {
      findings.push(...checkFrontend(file, source, config.frontend));
    }
    if (file.replaceAll('\\', '/').startsWith(`${config.backend.mainRoot}/`)) {
      findings.push(...checkBackend(file, source, config.backend));
    }
  }
  return findings;
}

module.exports = { checkDddRules, checkFrontend, checkBackend, loadRules };
