"use client";

import { useCallback, useEffect, useRef } from "react";
import { RoomEvent, Track } from "livekit-client";
import type { Participant, Room, VideoTrack } from "livekit-client";

import { useRoomConnection } from "./RoomProvider";

/** 카메라 트랙이 붙거나 떨어질 수 있는 LiveKit 이벤트(가이드 §7). */
const CAMERA_EVENTS: RoomEvent[] = [
  RoomEvent.ParticipantConnected,
  RoomEvent.ParticipantDisconnected,
  RoomEvent.TrackSubscribed,
  RoomEvent.TrackUnsubscribed,
  RoomEvent.TrackPublished,
  RoomEvent.TrackUnpublished,
  RoomEvent.TrackMuted,
  RoomEvent.TrackUnmuted,
  RoomEvent.LocalTrackPublished,
  RoomEvent.LocalTrackUnpublished,
  RoomEvent.ActiveDeviceChanged,
];

const cameraTrackOf = (participant: Participant): VideoTrack | undefined =>
  participant.getTrackPublication(Track.Source.Camera)?.videoTrack;

/** 로컬과 원격을 같은 목록으로 다룬다. 타일 입장에서 둘의 차이는 거울 반전뿐이다. */
const participantsOf = (room: Room | null): Participant[] =>
  room === null ? [] : [room.localParticipant, ...room.remoteParticipants.values()];

export type ParticipantVideos = {
  /**
   * 참가자 타일의 video 요소에 걸 ref. identity 마다 **같은 함수**를 돌려준다 — 매번 새 함수를 주면 React 가 렌더마다 ref 를 떼었다 붙여 화면이 깜빡인다.
   */
  refFor: (identity: string) => (element: HTMLVideoElement | null) => void;
};

/**
 * 참가자 타일에 카메라 화면을 붙인다. 로컬은 이미 publish 중인 트랙을, 원격은 구독된 트랙을 그대로 쓴다.
 *
 * <p>`getUserMedia` 를 다시 부르지 않는다. 카메라를 두 번 열면 장치 충돌이 나므로 LiveKit 이 열어 둔 트랙을 붙이기만 한다.
 *
 * <p>훅을 강의실 화면에서 한 번만 부르고 ref 를 타일로 내려보낸다. 타일마다 훅을 부르면 (1) 타일이 RoomProvider 없이는 렌더될 수 없게 되고, (2) 같은 참가자의 훅
 * 인스턴스가 둘이 되어 서로 트랙을 떼어낸다.
 */
export function useParticipantVideos(): ParticipantVideos {
  const { room } = useRoomConnection();
  // identity → 지금 화면에 있는 video 요소.
  const elements = useRef(new Map<string, HTMLVideoElement>());
  // identity → 붙여 둔 트랙·요소. 같은 짝에 중복으로 붙이거나 남의 요소를 떼지 않기 위해 기억한다.
  const attachments = useRef(new Map<string, { track: VideoTrack; element: HTMLVideoElement }>());
  // identity → ref 콜백. 함수 identity 를 고정하려고 캐시한다.
  const refs = useRef(new Map<string, (element: HTMLVideoElement | null) => void>());

  const detach = useCallback((identity: string) => {
    const current = attachments.current.get(identity);
    if (!current) {
      return;
    }
    attachments.current.delete(identity);
    current.track.detach(current.element);
  }, []);

  const sync = useCallback(() => {
    const present = new Set<string>();
    participantsOf(room).forEach((participant) => {
      const identity = participant.identity;
      present.add(identity);

      const element = elements.current.get(identity);
      const track = cameraTrackOf(participant);
      if (!element || !track) {
        detach(identity);
        return;
      }
      const current = attachments.current.get(identity);
      if (current?.track === track && current.element === element) {
        return;
      }
      detach(identity);
      track.attach(element);
      attachments.current.set(identity, { track, element });
    });

    // 나간 참가자의 트랙은 떼어낸다. 이벤트가 먼저 오고 타일이 사라지므로 여기서 정리해야 한다.
    [...attachments.current.keys()].forEach((identity) => {
      if (!present.has(identity)) {
        detach(identity);
      }
    });
  }, [detach, room]);

  // ref 콜백은 identity 마다 고정이라 최신 sync 를 클로저로 잡을 수 없다. 참조로 넘겨 최신 것을 부른다.
  const syncRef = useRef(sync);
  useEffect(() => {
    syncRef.current = sync;
  });

  const refFor = useCallback(
    (identity: string) => {
      const cached = refs.current.get(identity);
      if (cached) {
        return cached;
      }
      const ref = (element: HTMLVideoElement | null) => {
        if (element === null) {
          // 타일이 화면에서 사라졌다(페이지 이동·퇴장). 트랙을 떼고 다음 동기화 대상에서 뺀다.
          detach(identity);
          elements.current.delete(identity);
          return;
        }
        elements.current.set(identity, element);
        syncRef.current();
      };
      refs.current.set(identity, ref);
      return ref;
    },
    [detach],
  );

  useEffect(() => {
    // Map 객체 자체는 교체되지 않으므로 여기서 잡아 둬도 정리 시점의 최신 내용을 읽는다.
    const current = attachments.current;
    // 초기 동기화를 effect 본문 밖(지연)으로 빼서 렌더-이펙트 동기 부수효과를 피한다.
    const initial = setTimeout(sync, 0);

    if (!room) {
      return () => clearTimeout(initial);
    }

    CAMERA_EVENTS.forEach((event) => room.on(event, sync));
    return () => {
      clearTimeout(initial);
      CAMERA_EVENTS.forEach((event) => room.off(event, sync));
      [...current.keys()].forEach(detach);
    };
  }, [detach, room, sync]);

  return { refFor };
}
