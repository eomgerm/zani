"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { RoomEvent, Track } from "livekit-client";
import type { LocalVideoTrack } from "livekit-client";

import { useRoomConnection } from "./RoomProvider";

/** 로컬 카메라 트랙의 publish 상태가 바뀔 수 있는 LiveKit 이벤트(가이드 §7). */
const CAMERA_TRACK_EVENTS: RoomEvent[] = [
  RoomEvent.LocalTrackPublished,
  RoomEvent.LocalTrackUnpublished,
  RoomEvent.TrackMuted,
  RoomEvent.TrackUnmuted,
  RoomEvent.ActiveDeviceChanged,
];

export type LocalCameraVideo = {
  /** 로컬 카메라 트랙을 붙일 video 요소 ref. 화면에 보이지 않아도 된다. */
  videoRef: React.RefObject<HTMLVideoElement | null>;
  /** 현재 트랙이 요소에 붙어 있는지. false면 판정에 쓸 프레임이 없다. */
  attached: boolean;
};

/**
 * 이미 publish 중인 로컬 카메라 트랙을 video 요소에 붙여, 참여도 판정이 프레임을 읽을 수 있게 한다.
 *
 * `getUserMedia`를 다시 부르지 않는다. 카메라를 두 번 열면 장치 충돌이 나고 학생 화면이 끊기므로,
 * LiveKit이 이미 열어 둔 트랙을 그대로 붙인다. 트랙이 바뀌거나 사라지면 이전 요소에서 떼어낸다.
 */
export function useLocalCameraVideo(): LocalCameraVideo {
  const { room } = useRoomConnection();
  const videoRef = useRef<HTMLVideoElement | null>(null);
  // 지금 붙여 둔 트랙·요소를 기억해, 같은 트랙에 중복으로 붙이거나 남의 요소를 떼지 않는다.
  const attachedTo = useRef<{ track: LocalVideoTrack; element: HTMLVideoElement } | null>(null);
  const [attached, setAttached] = useState(false);

  const detach = useCallback(() => {
    const current = attachedTo.current;
    if (!current) {
      return;
    }
    attachedTo.current = null;
    current.track.detach(current.element);
    setAttached(false);
  }, []);

  const sync = useCallback(() => {
    const element = videoRef.current;
    const track = room?.localParticipant?.getTrackPublication(Track.Source.Camera)?.videoTrack;

    if (!element || !track) {
      detach();
      return;
    }
    const current = attachedTo.current;
    if (current?.track === track && current.element === element) {
      return;
    }
    detach();
    track.attach(element);
    attachedTo.current = { track, element };
    setAttached(true);
  }, [detach, room]);

  useEffect(() => {
    // 초기 동기화를 effect 본문 밖(지연)으로 빼서 렌더-이펙트 동기 setState를 피한다.
    const initial = setTimeout(sync, 0);

    if (!room) {
      return () => {
        clearTimeout(initial);
        detach();
      };
    }

    CAMERA_TRACK_EVENTS.forEach((event) => room.on(event, sync));
    return () => {
      clearTimeout(initial);
      CAMERA_TRACK_EVENTS.forEach((event) => room.off(event, sync));
      detach();
    };
  }, [detach, room, sync]);

  return { videoRef, attached };
}
