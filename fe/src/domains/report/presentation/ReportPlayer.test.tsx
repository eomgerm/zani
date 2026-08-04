import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { ReportPlayer } from "./ReportPlayer";

/**
 * jsdom 은 미디어 재생을 구현하지 않는다. 재생 자체는 검증 대상이 아니고, 이 컴포넌트가
 * 소유한 것은 "언제 어디로 currentTime 을 옮기고, 죽은 URL 을 어떻게 되살리는가"다.
 * duration·readyState 는 읽기 전용 getter 라 defineProperty 로 바꿔 끼운다.
 */
const videoEl = (): HTMLVideoElement => screen.getByTestId("report-video") as HTMLVideoElement;

const primeMetadata = (video: HTMLVideoElement, duration = 7530) => {
  Object.defineProperty(video, "duration", { value: duration, configurable: true });
  Object.defineProperty(video, "readyState", { value: 1, configurable: true });
  fireEvent.loadedMetadata(video);
};

describe("ReportPlayer", () => {
  it("녹화가 없으면 준비 전 안내를 그린다 — 오류가 아니다", () => {
    render(<ReportPlayer recordingUrl={null} title="React 상태관리" />);

    expect(screen.getByText("녹화가 아직 준비되지 않았어요")).toBeInTheDocument();
    expect(screen.queryByTestId("report-video")).not.toBeInTheDocument();
  });

  it("메타데이터 전에 온 이동 명령은 메타데이터가 오는 순간 적용한다", () => {
    render(
      <ReportPlayer
        recordingUrl="https://media.example/lecture.mp4?token=a"
        title="t"
        seekRequest={{ seconds: 512, nonce: 1 }}
      />,
    );

    const video = videoEl();
    expect(video.currentTime).toBe(0);

    primeMetadata(video);

    expect(video.currentTime).toBe(512);
  });

  it("메타데이터 이후의 이동 명령은 즉시 적용한다 — 같은 시각도 nonce 로 구분한다", () => {
    const { rerender } = render(
      <ReportPlayer recordingUrl="https://media.example/lecture.mp4?token=a" title="t" />,
    );

    const video = videoEl();
    primeMetadata(video);

    rerender(
      <ReportPlayer
        recordingUrl="https://media.example/lecture.mp4?token=a"
        title="t"
        seekRequest={{ seconds: 1440, nonce: 1 }}
      />,
    );
    expect(video.currentTime).toBe(1440);

    video.currentTime = 2000;
    rerender(
      <ReportPlayer
        recordingUrl="https://media.example/lecture.mp4?token=a"
        title="t"
        seekRequest={{ seconds: 1440, nonce: 2 }}
      />,
    );
    expect(video.currentTime).toBe(1440);
  });

  it("초기 위치는 자리만 잡고 재생하지 않는다", () => {
    const play = vi.fn();
    render(
      <ReportPlayer
        recordingUrl="https://media.example/lecture.mp4?token=a"
        title="t"
        initialSeconds={90}
      />,
    );

    const video = videoEl();
    Object.defineProperty(video, "play", { value: play, configurable: true });
    primeMetadata(video);

    expect(video.currentTime).toBe(90);
    expect(play).not.toHaveBeenCalled();
  });

  it("재생 위치를 timeupdate 마다 초 단위로 알린다", () => {
    const onTimeChange = vi.fn();
    render(
      <ReportPlayer
        recordingUrl="https://media.example/lecture.mp4?token=a"
        title="t"
        onTimeChange={onTimeChange}
      />,
    );

    const video = videoEl();
    video.currentTime = 42;
    fireEvent.timeUpdate(video);

    expect(onTimeChange).toHaveBeenCalledWith(42);
  });

  it("URL 이 죽으면 한 번 재발급해 같은 위치에서 잇는다", async () => {
    const reissueUrl = vi.fn(async () => "https://media.example/lecture.mp4?token=fresh");
    render(
      <ReportPlayer
        recordingUrl="https://media.example/lecture.mp4?token=stale"
        title="t"
        reissueUrl={reissueUrl}
      />,
    );

    const video = videoEl();
    primeMetadata(video);
    video.currentTime = 1234;
    fireEvent.timeUpdate(video);

    fireEvent.error(video);

    await waitFor(() => expect(videoEl().src).toContain("token=fresh"));
    expect(reissueUrl).toHaveBeenCalledTimes(1);

    // 새 src 의 메타데이터가 오면 죽기 직전 위치로 복원한다.
    primeMetadata(videoEl());
    expect(videoEl().currentTime).toBe(1234);
  });

  it("재발급이 실패하면 오류 안내를 그린다", async () => {
    const reissueUrl = vi.fn(async () => null);
    render(
      <ReportPlayer
        recordingUrl="https://media.example/lecture.mp4?token=stale"
        title="t"
        reissueUrl={reissueUrl}
      />,
    );

    fireEvent.error(videoEl());

    expect(await screen.findByText("녹화를 재생하지 못했어요")).toBeInTheDocument();
  });

  it("재발급은 한 번뿐이다 — 새 URL 마저 죽으면 오류로 끝낸다", async () => {
    const reissueUrl = vi.fn(async () => "https://media.example/lecture.mp4?token=fresh");
    render(
      <ReportPlayer
        recordingUrl="https://media.example/lecture.mp4?token=stale"
        title="t"
        reissueUrl={reissueUrl}
      />,
    );

    fireEvent.error(videoEl());
    await waitFor(() => expect(videoEl().src).toContain("token=fresh"));

    fireEvent.error(videoEl());

    expect(await screen.findByText("녹화를 재생하지 못했어요")).toBeInTheDocument();
    expect(reissueUrl).toHaveBeenCalledTimes(1);
  });
});
