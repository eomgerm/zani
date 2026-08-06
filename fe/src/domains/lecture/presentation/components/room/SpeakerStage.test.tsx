import { describe, expect, it } from "vitest";

import { spotlightIndex } from "./SpeakerStage";
import type { ParticipantTileData } from "./ParticipantTile";

const person = (
  id: string,
  role: "instructor" | "student",
  speaking = false,
): ParticipantTileData => ({
  id,
  name: id,
  color: "#2aa584",
  role,
  cameraEnabled: true,
  microphoneEnabled: true,
  handRaised: false,
  speaking,
});

/**
 * 발표자 보기가 누구를 띄울지는 순서가 규칙이다. 앞의 조건이 성립하면 뒤는 보지 않는다 —
 * 그 우선순위가 뒤집히면 공유 중에 발표 자료가 사라지거나, 아무도 말하지 않을 때 화면이 빈다.
 */
describe("spotlightIndex", () => {
  const instructorFirst = [person("i", "instructor"), person("s1", "student")];

  it("공유 중이면 말하는 사람이 있어도 공유 화면을 띄운다", () => {
    const speaking = [person("i", "instructor"), person("s1", "student", true)];

    expect(spotlightIndex(speaking, true)).toBe(0);
  });

  it("공유가 없으면 말하는 사람을 띄운다 — 강사보다 먼저다", () => {
    const speaking = [person("i", "instructor"), person("s1", "student", true)];

    expect(spotlightIndex(speaking, false)).toBe(1);
  });

  it("아무도 말하지 않으면 강사를 띄운다", () => {
    const studentFirst = [person("s1", "student"), person("i", "instructor")];

    expect(spotlightIndex(studentFirst, false)).toBe(1);
  });

  it("강사가 없으면 목록의 첫 사람을 띄운다", () => {
    const students = [person("s1", "student"), person("s2", "student")];

    expect(spotlightIndex(students, false)).toBe(0);
  });

  /** 아직 아무도 붙지 않은 순간이다. 빈 화면 대신 기다린다는 것을 알려야 한다. */
  it("공유도 참가자도 없으면 띄울 대상이 없다", () => {
    expect(spotlightIndex([], false)).toBeNull();
  });

  it("강사가 맨 앞이면 그대로 강사를 띄운다", () => {
    expect(spotlightIndex(instructorFirst, false)).toBe(0);
  });
});
