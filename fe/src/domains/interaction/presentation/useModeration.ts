"use client";

import { useCallback, useState } from "react";

import { useAuth } from "@/domains/auth";
import {
  muteParticipant as defaultMuteParticipant,
  type ParticipantMuter,
} from "../infrastructure/moderationApi";

/**
 * 강사 강제 음소거.
 *
 * ## 마이크 상태는 여기서 다루지 않는다
 *
 * 서버가 LiveKit 에 mute 를 걸면 LiveKit 이 모든 참가자에게 트랙 상태를 전파하고, `useRoomParticipants`
 * 가 `RoomEvent.TrackMuted` 로 그것을 받는다. 그래서 대상 본인의 마이크 표시도 남의 목록도 이미 맞는다.
 *
 * 그래서 서버는 이 결과를 STOMP 로 뿌리지 않는다. 뿌리면 한 값을 두 출처가 다투게 되고, LiveKit 이
 * 진실인데 STOMP 가 늦거나 빠지면 화면이 어긋난다.
 *
 * ## 당한 학생에게 안내하지 않는다
 *
 * 이 기능이 쓰이는 상황은 **학생이 마이크가 켜진 줄 모르는 때**다(생활 소음이 수업에 섞이는 경우). 켜진
 * 줄 몰랐던 사람에게 "꺼졌다"고 알리는 것은 묻지도 않은 것을 알리는 셈이라 오히려 방해가 된다. 마이크
 * 아이콘이 이미 상태를 보여주므로, 확인하려는 학생은 그것으로 안다.
 */

export interface UseModerationOptions {
  readonly sessionId: string;
  muteParticipant?: ParticipantMuter;
}

export interface UseModerationResult {
  /** 지금 요청 중인 대상의 identity. 그 타일의 버튼만 잠그는 데 쓴다. */
  readonly mutingIdentity: string | null;
  /** 마지막 요청이 실패한 이유. 성공하거나 다시 시도하면 지워진다. */
  readonly muteError: string | null;
  /** 대상은 LiveKit identity(`p-{참가자ID}`)로 받는다 — 화면이 들고 있는 값이 그것뿐이다. */
  mute: (targetIdentity: string) => Promise<void>;
}

/**
 * identity 에서 참가자 ID 를 떼어낸다.
 *
 * 화면은 LiveKit identity 만 들고 있고 서버 API 는 참가자 ID 를 받는다. 이 변환을 화면에 두면 identity
 * 형식을 아는 곳이 늘어나므로, 그 형식을 소유한 이 도메인 안에서 끝낸다.
 */
function participantIdOf(identity: string): string | null {
  const id = identity.startsWith("p-") ? identity.slice(2) : "";
  return id.length > 0 ? id : null;
}

/** 상태별 문구. 서버 오류 코드를 그대로 띄우지 않는다 — 강사가 읽고 다음 행동을 정할 수 있어야 한다. */
function messageFor(status: number): string {
  if (status === 403) return "이 수업의 강사만 학생을 음소거할 수 있어요.";
  if (status === 404) return "대상 참가자를 찾을 수 없어요. 이미 나갔을 수 있어요.";
  if (status === 409) return "이미 종료된 수업이에요.";
  if (status === 503) return "미디어 서버를 쓸 수 없어 음소거하지 못했어요. 잠시 뒤 다시 시도해 주세요.";
  return "음소거하지 못했어요. 잠시 뒤 다시 시도해 주세요.";
}

export function useModeration(options: UseModerationOptions): UseModerationResult {
  const { sessionId, muteParticipant = defaultMuteParticipant } = options;
  const { accessToken } = useAuth();

  const [mutingIdentity, setMutingIdentity] = useState<string | null>(null);
  const [muteError, setMuteError] = useState<string | null>(null);

  const mute = useCallback(
    async (targetIdentity: string) => {
      const targetParticipantId = participantIdOf(targetIdentity);
      if (accessToken === null || targetParticipantId === null || mutingIdentity !== null) return;

      setMutingIdentity(targetIdentity);
      setMuteError(null);
      try {
        await muteParticipant(sessionId, targetParticipantId, accessToken);
      } catch (failed) {
        // 실패를 삼키면 강사 화면에는 음소거로 보이는데 학생 소리는 계속 나간다. 반드시 알린다.
        const status = failed instanceof Error && "status" in failed ? Number(failed.status) : 0;
        setMuteError(messageFor(status));
      } finally {
        setMutingIdentity(null);
      }
    },
    [accessToken, mutingIdentity, muteParticipant, sessionId],
  );

  return { mutingIdentity, muteError, mute };
}
