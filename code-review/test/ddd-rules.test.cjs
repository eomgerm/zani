const assert = require('node:assert/strict');
const test = require('node:test');

const { checkDddRules } = require('../lib/ddd-rules.cjs');

function checkFiles(files) {
  return checkDddRules({
    repositoryRoot: 'C:/repo',
    changedFiles: Object.keys(files),
  }, {
    readFile(file) {
      return files[file];
    },
    fileExists(file) {
      return Object.hasOwn(files, file);
    },
  });
}

test('frontend domain cannot import React or browser persistence APIs', () => {
  const findings = checkFiles({
    'fe/src/domains/room/domain/model/room.ts': [
      "import React from 'react';",
      "localStorage.setItem('room', '1');",
    ].join('\n'),
  });

  assert.equal(findings.length, 2);
  assert.ok(findings.every((finding) => finding.type === 'frontend-ddd'));
  assert.deepEqual(findings.map((finding) => finding.line), [1, 2]);
});

test('frontend domain cannot import outer layers from the same domain', () => {
  const findings = checkFiles({
    'fe/src/domains/room/domain/model/room.ts':
      "import { joinRoom } from '@/domains/room/application/join-room';",
  });

  assert.equal(findings.length, 1);
  assert.match(findings[0].message, /application/);
});

test('frontend application cannot import presentation or infrastructure', () => {
  const findings = checkFiles({
    'fe/src/domains/room/application/join-room.ts': [
      "import { RoomView } from '../presentation/RoomView';",
      "import { roomApi } from '../infrastructure/room-api';",
    ].join('\n'),
  });

  assert.equal(findings.length, 2);
  assert.ok(findings.every((finding) => finding.severity === 'blocker'));
});

test('frontend consumers outside a domain must use its index entry point', () => {
  const findings = checkFiles({
    'fe/src/app/page.tsx': "import { login } from '@/domains/auth/application/login';",
  });

  assert.equal(findings.length, 1);
  assert.match(findings[0].message, /index\.ts/);
});

test('frontend consumers may explicitly import a domain index entry point', () => {
  const findings = checkFiles({
    'fe/src/app/page.tsx': "import { login } from '@/domains/auth/index';",
  });

  assert.deepEqual(findings, []);
});

test('valid frontend presentation to application dependency passes', () => {
  const findings = checkFiles({
    'fe/src/domains/room/presentation/RoomView.tsx':
      "import { joinRoom } from '../application/join-room';",
  });

  assert.deepEqual(findings, []);
});

test('frontend files may use an internal alias within the same domain', () => {
  const findings = checkFiles({
    'fe/src/domains/room/presentation/RoomView.tsx':
      "import { joinRoom } from '@/domains/room/application/join-room';",
  });

  assert.deepEqual(findings, []);
});

test('backend domain remains pure and cannot use JPA', () => {
  const findings = checkFiles({
    'backend/src/main/java/com/a105/zani/room/domain/model/Room.java': [
      'package com.a105.zani.room.domain.model;',
      'import jakarta.persistence.Entity;',
      '@Entity',
      'class Room {}',
    ].join('\n'),
  });

  assert.ok(findings.some((finding) => finding.line === 2));
  assert.ok(findings.some((finding) => /JPA Entity/.test(finding.message)));
});

test('backend application cannot depend on infrastructure', () => {
  const findings = checkFiles({
    'backend/src/main/java/com/a105/zani/room/application/joinroom/JoinRoomService.java': [
      'package com.a105.zani.room.application.joinroom;',
      'import com.a105.zani.room.infrastructure.persistence.RoomJpaEntity;',
      'class JoinRoomService {}',
    ].join('\n'),
  });

  assert.equal(findings.length, 1);
  assert.match(findings[0].message, /infrastructure/);
});

test('backend domains cannot import another domain infrastructure', () => {
  const findings = checkFiles({
    'backend/src/main/java/com/a105/zani/report/application/getreport/GetReportService.java': [
      'package com.a105.zani.report.application.getreport;',
      'import com.a105.zani.lecture.infrastructure.persistence.LectureJpaEntity;',
      'class GetReportService {}',
    ].join('\n'),
  });

  assert.equal(findings.length, 1);
  assert.match(findings[0].message, /다른 도메인/);
});

test('backend application rejects global technical packages', () => {
  const findings = checkFiles({
    'backend/src/main/java/com/a105/zani/room/application/command/CreateRoomCommand.java':
      'package com.a105.zani.room.application.command; class CreateRoomCommand {}',
  });

  assert.equal(findings.length, 1);
  assert.match(findings[0].message, /업무 유스케이스/);
});

test('valid backend infrastructure adapter dependency passes', () => {
  const findings = checkFiles({
    'backend/src/main/java/com/a105/zani/room/infrastructure/persistence/RoomPersistenceAdapter.java': [
      'package com.a105.zani.room.infrastructure.persistence;',
      'import com.a105.zani.room.domain.repository.RoomRepository;',
      'class RoomPersistenceAdapter implements RoomRepository {}',
    ].join('\n'),
  });

  assert.deepEqual(findings, []);
});

test('deleted source files are skipped instead of aborting the review', () => {
  const findings = checkDddRules({
    repositoryRoot: 'C:/repo',
    changedFiles: ['fe/src/domains/room/domain/model/DeletedRoom.ts'],
  }, {
    fileExists: () => false,
    readFile: () => { throw new Error('ENOENT: deleted file'); },
  });

  assert.deepEqual(findings, []);
});
