import { describe, expect, it } from "vitest";

import { findFirstClusterOffset, splitFirstWebmChunk } from "./webmHeader";

// EBML 헤더를 흉내낸 앞부분. 값 자체는 검증에 쓰이지 않고 길이만 의미가 있다.
const FAKE_EBML_HEADER = [0x1a, 0x45, 0xdf, 0xa3, 0x86, 0x81, 0x01, 0x42, 0x86];

const CLUSTER_ID = [0x1f, 0x43, 0xb6, 0x75];
const TIMECODE = [0xe7, 0x81, 0x00];

const bytes = (...parts: number[][]): Uint8Array<ArrayBuffer> => new Uint8Array(parts.flat());

describe("findFirstClusterOffset", () => {
  it("1바이트 unknown-size vint 뒤에 Timecode가 오는 Cluster를 찾는다", () => {
    const input = bytes(FAKE_EBML_HEADER, CLUSTER_ID, [0xff], TIMECODE, [0xa3, 0x01]);
    expect(findFirstClusterOffset(input)).toBe(FAKE_EBML_HEADER.length);
  });

  it("8바이트 unknown-size vint(0x01 FF...) 뒤의 Timecode도 인식한다", () => {
    const size = [0x01, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff];
    const input = bytes(FAKE_EBML_HEADER, CLUSTER_ID, size, TIMECODE);
    expect(findFirstClusterOffset(input)).toBe(FAKE_EBML_HEADER.length);
  });

  it("조각 맨 앞에서 시작하는 Cluster는 오프셋 0을 반환한다", () => {
    const input = bytes(CLUSTER_ID, [0xff], TIMECODE);
    expect(findFirstClusterOffset(input)).toBe(0);
  });

  it("payload에 우연히 나온 Cluster ID(뒤에 Timecode 없음)는 건너뛰고 진짜 Cluster를 찾는다", () => {
    const falsePositive = [...CLUSTER_ID, 0xff, 0xa3]; // Timecode(0xE7) 대신 SimpleBlock(0xA3)
    const input = bytes(FAKE_EBML_HEADER, falsePositive, CLUSTER_ID, [0xff], TIMECODE);
    expect(findFirstClusterOffset(input)).toBe(
      FAKE_EBML_HEADER.length + falsePositive.length,
    );
  });

  it("사이즈 vint 첫 바이트가 0x00이면 유효한 Cluster로 보지 않는다", () => {
    const input = bytes(FAKE_EBML_HEADER, CLUSTER_ID, [0x00], TIMECODE);
    expect(findFirstClusterOffset(input)).toBe(-1);
  });

  it("Cluster ID가 없으면 -1을 반환한다", () => {
    expect(findFirstClusterOffset(bytes(FAKE_EBML_HEADER))).toBe(-1);
    expect(findFirstClusterOffset(new Uint8Array(0))).toBe(-1);
  });

  it("조각 끝에 걸려 검증할 바이트가 부족하면 -1을 반환한다", () => {
    expect(findFirstClusterOffset(bytes(FAKE_EBML_HEADER, CLUSTER_ID))).toBe(-1);
    expect(findFirstClusterOffset(bytes(FAKE_EBML_HEADER, CLUSTER_ID, [0xff]))).toBe(-1);
  });
});

describe("splitFirstWebmChunk", () => {
  const mimeType = "audio/webm;codecs=opus";

  it("헤더와 첫 Cluster부터의 본문으로 나누고 MIME 타입을 유지한다", async () => {
    const clusterAndAudio = [...CLUSTER_ID, 0xff, ...TIMECODE, 0xa3, 0x01, 0x02];
    const chunk = new Blob([bytes(FAKE_EBML_HEADER, clusterAndAudio)], { type: mimeType });

    const { header, body } = await splitFirstWebmChunk(chunk);

    expect(header.size).toBe(FAKE_EBML_HEADER.length);
    expect(header.type).toBe(mimeType);
    expect(body).not.toBeNull();
    expect(body?.size).toBe(clusterAndAudio.length);
    expect(body?.type).toBe(mimeType);
  });

  it("Cluster를 찾지 못하면 조각 전체를 헤더로 보고 body는 null이다", async () => {
    const chunk = new Blob([bytes(FAKE_EBML_HEADER)], { type: mimeType });

    const { header, body } = await splitFirstWebmChunk(chunk);

    expect(header.size).toBe(FAKE_EBML_HEADER.length);
    expect(body).toBeNull();
  });
});
