"use client";

import { useEffect, useState } from "react";
import { RoomEvent } from "livekit-client";
import type { Participant, Room } from "livekit-client";

import { useRoomConnection } from "./RoomProvider";
import type { ParticipantTileData } from "./components/room/ParticipantTile";

// 타일 원형 아바타 색. identity 기준으로 결정적 배정한다.
// 어두운 스테이지 위에 올라가므로 프로토타입 participantsMeta의 채도 낮은 팔레트를 쓴다.
const TILE_COLORS = [
  "#c9a24b",
  "#2aa584",
  "#c07284",
  "#57ad97",
  "#5e9ec6",
  "#c88d5d",
  "#66b195",
  "#6d8fc2",
  "#9c87cc",
  "#b981a0",
  "#8681c8",
];

function colorFor(identity: string): string {
  let hash = 0;
  for (let i = 0; i < identity.length; i += 1) {
    hash = (hash * 31 + identity.charCodeAt(i)) >>> 0;
  }
  return TILE_COLORS[hash % TILE_COLORS.length];
}

/**
 * 역할은 백엔드가 토큰 metadata(JSON)에 심어 보낸다. LiveKit 서버 SDK 0.14.0에는 attributes 설정 API가 없어
 * metadata로 전달되므로 여기서도 metadata에서 읽는다. 값이 없거나 JSON이 깨져도 화면이 죽지 않도록 student로 폴백한다.
 */
function roleOf(participant: Participant): ParticipantTileData["role"] {
  const raw = participant.metadata;
  if (!raw) {
    return "student";
  }
  try {
    const parsed = JSON.parse(raw) as { role?: unknown };
    return parsed?.role === "INSTRUCTOR" ? "instructor" : "student";
  } catch {
    return "student";
  }
}

/**
 * 백엔드가 토큰에 심은 값만 사용한다(가이드 §2). identity·표시 이름·역할을 프론트가 만들지 않는다.
 * 손들기는 LiveKit이 아니라 Spring WebSocket(가이드 §10) 소관이라 여기서는 false로 둔다.
 */
function toTileData(participant: Participant): ParticipantTileData {
  return {
    id: participant.identity,
    name: participant.name || participant.identity,
    color: colorFor(participant.identity),
    role: roleOf(participant),
    cameraEnabled: participant.isCameraEnabled,
    microphoneEnabled: participant.isMicrophoneEnabled,
    handRaised: false,
  };
}

function snapshot(room: Room | null): ParticipantTileData[] {
  if (!room) {
    return [];
  }
  const local = room.localParticipant ? [toTileData(room.localParticipant)] : [];
  const remotes = Array.from(room.remoteParticipants.values()).map(toTileData);
  return [...local, ...remotes];
}

// 참가자 목록·트랙 상태에 영향을 주는 LiveKit 이벤트(가이드 §7).
const PARTICIPANT_EVENTS: RoomEvent[] = [
  RoomEvent.ParticipantConnected,
  RoomEvent.ParticipantDisconnected,
  RoomEvent.TrackPublished,
  RoomEvent.TrackUnpublished,
  RoomEvent.TrackSubscribed,
  RoomEvent.TrackUnsubscribed,
  RoomEvent.TrackMuted,
  RoomEvent.TrackUnmuted,
  RoomEvent.LocalTrackPublished,
  RoomEvent.LocalTrackUnpublished,
  // 역할은 metadata에서 읽으므로 metadata 변경을 구독한다.
  RoomEvent.ParticipantMetadataChanged,
];

export type UseRoomParticipantsResult = {
  participants: ParticipantTileData[];
  localParticipantId: string | null;
};

/**
 * RoomProvider가 제공하는 실제 LiveKit room을 구독해 참가자·트랙 상태를
 * ParticipantTileData 목록으로 노출한다. 관련 이벤트마다 목록을 재계산하고,
 * 언마운트/room 교체 시 리스너를 모두 해제한다.
 */
export function useRoomParticipants(): UseRoomParticipantsResult {
  const { room } = useRoomConnection();
  const [participants, setParticipants] = useState<ParticipantTileData[]>([]);

  useEffect(() => {
    const update = () => setParticipants(snapshot(room));
    // 초기 동기화를 effect 본문 밖(지연)으로 빼서 렌더-이펙트 동기 setState를 피한다.
    const initial = setTimeout(update, 0);

    if (!room) {
      return () => clearTimeout(initial);
    }

    PARTICIPANT_EVENTS.forEach((event) => room.on(event, update));
    return () => {
      clearTimeout(initial);
      PARTICIPANT_EVENTS.forEach((event) => room.off(event, update));
    };
  }, [room]);

  return {
    participants,
    localParticipantId: room?.localParticipant?.identity ?? null,
  };
}
