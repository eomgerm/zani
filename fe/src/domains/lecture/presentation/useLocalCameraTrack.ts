"use client";

import { useCallback, useEffect, useState } from "react";
import { RoomEvent, Track } from "livekit-client";

import { useRoomConnection } from "./RoomProvider";

/** 로컬 카메라 트랙의 publish 상태가 바뀔 수 있는 LiveKit 이벤트(가이드 §7). */
const CAMERA_TRACK_EVENTS: RoomEvent[] = [
  RoomEvent.LocalTrackPublished,
  RoomEvent.LocalTrackUnpublished,
  RoomEvent.TrackMuted,
  RoomEvent.TrackUnmuted,
  RoomEvent.ActiveDeviceChanged,
];

export type LocalCameraTrack = {
  /** 참여도 판정이 프레임을 읽을 로컬 카메라 트랙. publish 전이거나 끊기면 null. */
  track: MediaStreamTrack | null;
};

/**
 * 이미 publish 중인 로컬 카메라의 `MediaStreamTrack` 을 참여도 판정에 넘긴다.
 *
 * `getUserMedia` 를 다시 부르지 않는다. 카메라를 두 번 열면 장치 충돌이 나고 학생 화면이
 * 끊기므로, LiveKit 이 이미 열어 둔 트랙을 그대로 쓴다.
 *
 * 판정은 Worker 에서 `MediaStreamTrackProcessor` 로 트랙에서 직접 프레임을 뽑기 때문에
 * 트랙을 붙일 video 요소가 필요하지 않다. 같은 트랙이면 참조를 그대로 유지해야 한다
 * (참조가 바뀌면 판정 세션이 재시작된다).
 */
export function useLocalCameraTrack(): LocalCameraTrack {
  const { room } = useRoomConnection();
  const [track, setTrack] = useState<MediaStreamTrack | null>(null);

  const sync = useCallback(() => {
    const published = room?.localParticipant?.getTrackPublication(Track.Source.Camera)?.videoTrack;
    // 같은 트랙이면 React 가 렌더를 건너뛰므로 이벤트가 반복돼도 세션은 유지된다.
    setTrack(published?.mediaStreamTrack ?? null);
  }, [room]);

  useEffect(() => {
    // 초기 동기화를 effect 본문 밖(지연)으로 빼서 렌더-이펙트 동기 setState 를 피한다.
    const initial = setTimeout(sync, 0);

    if (!room) {
      return () => {
        clearTimeout(initial);
        setTrack(null);
      };
    }

    CAMERA_TRACK_EVENTS.forEach((event) => room.on(event, sync));
    return () => {
      clearTimeout(initial);
      CAMERA_TRACK_EVENTS.forEach((event) => room.off(event, sync));
      setTrack(null);
    };
  }, [room, sync]);

  return { track };
}
