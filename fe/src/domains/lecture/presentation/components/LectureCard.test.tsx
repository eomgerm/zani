import { cleanup, fireEvent, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { LectureCard } from "./LectureCard";
import type { MyLecture } from "../myLectures";

const lecture = (over: Partial<MyLecture> = {}): MyLecture => ({
  id: "l1",
  title: "CS 네트워크 기초",
  date: "2026-07-10",
  startedAt: "2026-07-10T01:00:00Z",
  role: "student",
  status: "COMPLETED",
  dur: "1시간 12분",
  students: 12,
  instructor: "박서준",
  rejoinable: false,
  thumbnailUrl: null,
  ...over,
});

afterEach(cleanup);

describe("LectureCard 썸네일", () => {
  it("주소가 있으면 녹화 1/2 지점 프레임을 그린다", () => {
    const url = "https://zani.example/api/v1/sessions/1/thumbnail?expires=1&token=t";
    const { container } = render(<LectureCard lecture={lecture({ thumbnailUrl: url })} />);

    expect(container.querySelector("img")?.getAttribute("src")).toBe(url);
  });

  it("주소가 없으면(진행 중·병합 전·추출 실패) 자리 그림을 그린다", () => {
    const { container } = render(<LectureCard lecture={lecture()} />);

    expect(container.querySelector("img")).toBeNull();
  });

  /** 분석 중에는 아직 열 수 없는 강의라, 다 만들어진 화면처럼 보이는 자리 그림 대신 스피너만 돈다. */
  it("분석 중이면 주소가 있어도 스피너를 그린다", () => {
    const { container } = render(
      <LectureCard
        lecture={lecture({ status: "PROCESSING", thumbnailUrl: "https://zani.example/t.png" })}
      />,
    );

    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector('[class*="zSpin"]')).not.toBeNull();
  });

  /** 서명 주소는 짧게 살아서, 화면을 오래 두면 만료된 채 로드될 수 있다. 깨진 이미지 아이콘을 그대로 두면 안 된다. */
  it("이미지가 깨지면 자리 그림으로 되돌린다", () => {
    const { container } = render(
      <LectureCard lecture={lecture({ thumbnailUrl: "https://zani.example/broken.png" })} />,
    );

    fireEvent.error(container.querySelector("img")!);

    expect(container.querySelector("img")).toBeNull();
  });

  /* 첫 화면 행은 LCP 후보라 바로 받고, 접힌 카드는 스크롤이 닿을 때 받는다(/my-lectures Lighthouse). */

  it("기본은 접힌 카드로 보고 lazy 로 받는다", () => {
    const { container } = render(
      <LectureCard lecture={lecture({ thumbnailUrl: "https://zani.example/t.png" })} />,
    );

    const img = container.querySelector("img")!;
    expect(img.getAttribute("loading")).toBe("lazy");
    expect(img.getAttribute("fetchpriority")).toBeNull();
  });

  it("첫 화면 카드(priority)는 lazy 없이 높은 우선순위로 받는다", () => {
    const { container } = render(
      <LectureCard lecture={lecture({ thumbnailUrl: "https://zani.example/t.png" })} priority />,
    );

    const img = container.querySelector("img")!;
    expect(img.getAttribute("loading")).toBeNull();
    expect(img.getAttribute("fetchpriority")).toBe("high");
  });
});
