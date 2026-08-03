"use client";

import { useCallback, useEffect, useRef } from "react";
import { RoomEvent, Track } from "livekit-client";
import type { AudioTrack, Room } from "livekit-client";

/** 원격 오디오가 붙거나 떨어질 수 있는 LiveKit 이벤트(가이드 §7). */
const AUDIO_EVENTS: RoomEvent[] = [
  RoomEvent.ParticipantConnected,
  RoomEvent.ParticipantDisconnected,
  RoomEvent.TrackSubscribed,
  RoomEvent.TrackUnsubscribed,
  RoomEvent.TrackPublished,
  RoomEvent.TrackUnpublished,
  // 브라우저 자동재생 정책으로 소리가 막혔다 풀릴 때 다시 재생을 시도한다.
  RoomEvent.AudioPlaybackStatusChanged,
];

/** 원격 참가자들이 구독 중인 오디오 트랙(마이크·화면공유 소리)만 모은다. 내 마이크는 내가 듣지 않는다. */
const remoteAudioTracksOf = (room: Room): AudioTrack[] => {
  const tracks: AudioTrack[] = [];
  room.remoteParticipants.forEach((participant) => {
    participant.trackPublications.forEach((publication) => {
      if (publication.kind === Track.Kind.Audio && publication.audioTrack) {
        tracks.push(publication.audioTrack);
      }
    });
  });
  return tracks;
};

/**
 * 원격 참가자의 오디오를 실제로 들리게 한다. 타일의 video 요소는 하울링을 막으려 모두 muted 라, 마이크 소리는
 * 이 훅이 audio 요소에 따로 붙여 재생한다. 붙이지 않으면 서로의 마이크가 켜져 있어도 소리가 나지 않는다.
 *
 * <p>카메라를 붙이는 {@link useParticipantVideos} 와 같은 규칙이다 — 트랙마다 요소 하나에만 붙이고, 트랙이
 * 사라지면 떼어낸다. 다만 오디오 요소는 화면에 없어 우리가 만들어 document 에 붙였다 뗀다.
 *
 * <p>강의실 화면에서 한 번만 부른다.
 */
export function useRemoteAudio(room: Room | null): void {
  // 트랙 → 붙여 둔 audio 요소. 같은 트랙에 두 번 붙이거나 남은 요소를 흘리지 않으려 기억한다.
  const attachments = useRef(new Map<AudioTrack, HTMLAudioElement>());

  const detach = useCallback((track: AudioTrack) => {
    const element = attachments.current.get(track);
    if (!element) {
      return;
    }
    attachments.current.delete(track);
    track.detach(element);
    element.remove();
  }, []);

  const sync = useCallback(() => {
    if (!room) {
      return;
    }
    const present = new Set<AudioTrack>(remoteAudioTracksOf(room));
    present.forEach((track) => {
      if (attachments.current.has(track)) {
        return;
      }
      // 오디오는 화면에 없다. 요소를 만들어 트랙을 붙이고 document 에 넣어 재생시킨다.
      const element = document.createElement("audio");
      element.autoplay = true;
      element.style.display = "none";
      track.attach(element);
      document.body.appendChild(element);
      attachments.current.set(track, element);
    });

    // 나간 참가자·꺼진 마이크의 트랙은 떼어낸다.
    [...attachments.current.keys()].forEach((track) => {
      if (!present.has(track)) {
        detach(track);
      }
    });

    // 사용자가 아직 페이지를 건드리지 않아 자동재생이 막혀 있으면 풀어 본다. 제스처가 없으면 조용히 실패한다.
    if (present.size > 0 && room.canPlaybackAudio === false) {
      void room.startAudio().catch(() => {});
    }
  }, [detach, room]);

  useEffect(() => {
    const current = attachments.current;
    // 초기 동기화를 effect 본문 밖(지연)으로 빼서 렌더-이펙트 동기 부수효과를 피한다.
    const initial = setTimeout(sync, 0);

    if (!room) {
      return () => clearTimeout(initial);
    }

    AUDIO_EVENTS.forEach((event) => room.on(event, sync));
    return () => {
      clearTimeout(initial);
      AUDIO_EVENTS.forEach((event) => room.off(event, sync));
      [...current.keys()].forEach((track) => {
        const element = current.get(track);
        if (element) {
          current.delete(track);
          track.detach(element);
          element.remove();
        }
      });
    };
  }, [room, sync]);
}
