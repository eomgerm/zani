"use client";

import { useCallback, useEffect, useState } from "react";
import { RoomEvent } from "livekit-client";
import type { Room } from "livekit-client";

import { useRoomConnection } from "./RoomProvider";

/** 로컬 publish 상태가 바뀔 수 있는 LiveKit 이벤트(가이드 §7). */
const MEDIA_EVENTS: RoomEvent[] = [
  RoomEvent.LocalTrackPublished,
  RoomEvent.LocalTrackUnpublished,
  RoomEvent.TrackMuted,
  RoomEvent.TrackUnmuted,
];

const TOGGLE_FAILURE_MESSAGE = "장치를 사용할 수 없습니다. 연결과 권한을 확인해 주세요.";

export type RoomMediaControls = {
  /** 로컬 마이크가 publish 중인지. */
  microphoneEnabled: boolean;
  /** 로컬 카메라가 publish 중인지. */
  cameraEnabled: boolean;
  /** room에 연결돼 조작할 수 있는 상태인지. false면 제어 UI를 비활성화한다. */
  ready: boolean;
  /** 장치 조작 실패 안내. 다음 성공 시 null로 돌아간다. */
  mediaError: string | null;
  toggleMicrophone: () => void;
  toggleCamera: () => void;
};

type MediaSnapshot = Pick<RoomMediaControls, "microphoneEnabled" | "cameraEnabled" | "ready">;

const disconnectedSnapshot: MediaSnapshot = {
  microphoneEnabled: false,
  cameraEnabled: false,
  ready: false,
};

function snapshot(room: Room | null): MediaSnapshot {
  const local = room?.localParticipant;
  if (!local) {
    return disconnectedSnapshot;
  }
  return {
    microphoneEnabled: local.isMicrophoneEnabled,
    cameraEnabled: local.isCameraEnabled,
    ready: true,
  };
}

/**
 * RoomProvider가 제공하는 실제 LiveKit room의 로컬 트랙 publish를 제어한다.
 * 화면 상태는 localParticipant를 그대로 스냅샷하므로, 서버·다른 기기에서 트랙이 바뀌어도 같은 값을 보여준다.
 * 언마운트/room 교체 시 리스너를 모두 해제한다.
 */
export function useRoomMediaControls(): RoomMediaControls {
  const { room } = useRoomConnection();
  const [state, setState] = useState<MediaSnapshot>(disconnectedSnapshot);
  const [mediaError, setMediaError] = useState<string | null>(null);

  useEffect(() => {
    const update = () => setState(snapshot(room));
    // 초기 동기화를 effect 본문 밖(지연)으로 빼서 렌더-이펙트 동기 setState를 피한다.
    const initial = setTimeout(update, 0);

    if (!room) {
      return () => clearTimeout(initial);
    }

    MEDIA_EVENTS.forEach((event) => room.on(event, update));
    return () => {
      clearTimeout(initial);
      MEDIA_EVENTS.forEach((event) => room.off(event, update));
    };
  }, [room]);

  const toggle = useCallback(
    (kind: "microphone" | "camera") => {
      const local = room?.localParticipant;
      if (!local) {
        return;
      }
      const applied =
        kind === "microphone"
          ? local.setMicrophoneEnabled(!local.isMicrophoneEnabled)
          : local.setCameraEnabled(!local.isCameraEnabled);
      void Promise.resolve(applied).then(
        () => {
          setMediaError(null);
          setState(snapshot(room));
        },
        () => setMediaError(TOGGLE_FAILURE_MESSAGE),
      );
    },
    [room],
  );

  const toggleMicrophone = useCallback(() => toggle("microphone"), [toggle]);
  const toggleCamera = useCallback(() => toggle("camera"), [toggle]);

  return { ...state, mediaError, toggleMicrophone, toggleCamera };
}
