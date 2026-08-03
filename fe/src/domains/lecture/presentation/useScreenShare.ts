"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { RoomEvent, Track } from "livekit-client";
import type { Participant, Room, VideoTrack } from "livekit-client";

import { useAuth } from "@/domains/auth";
import { useRoomConnection } from "./RoomProvider";
import {
  ScreenShareRequestError,
  claimScreenShare,
  releaseScreenShare,
  type ScreenShareClaimer,
  type ScreenShareReleaser,
} from "../infrastructure/screenShareApi";

/** 슬롯 갱신 주기(ms). 서버 슬롯 TTL(30초)보다 넉넉히 짧게 잡아, 한 번 실패해도 슬롯이 유지된다. */
const CLAIM_REFRESH_MS = 10_000;

/** 화면 공유 트랙이 붙거나 떨어질 수 있는 LiveKit 이벤트(가이드 §7). */
const SCREEN_EVENTS: RoomEvent[] = [
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
];

const participantsOf = (room: Room | null): Participant[] =>
  room === null ? [] : [room.localParticipant, ...room.remoteParticipants.values()];

const screenTrackOf = (participant: Participant): VideoTrack | undefined =>
  participant.getTrackPublication(Track.Source.ScreenShare)?.videoTrack;

/** 지금 화면을 공유 중인 참가자(로컬 포함). 없으면 null. LiveKit이 상태를 실어 나르므로 서버에 묻지 않는다. */
const activeSharerOf = (room: Room | null): Participant | null =>
  participantsOf(room).find((participant) => participant.isScreenShareEnabled) ?? null;

type ScreenShareSnapshot = {
  /** 내가 지금 화면을 공유 중인지. */
  sharing: boolean;
  /** 지금 공유 중인 참가자 identity(로컬 포함). 아무도 공유하지 않으면 null. */
  activeIdentity: string | null;
};

const IDLE: ScreenShareSnapshot = { sharing: false, activeIdentity: null };

export type UseScreenShareOptions = {
  claim?: ScreenShareClaimer;
  release?: ScreenShareReleaser;
  /** 슬롯 갱신 주기(ms). 테스트에서 짧게 조정한다. */
  refreshMs?: number;
};

export type ScreenShareState = {
  /** 내가 지금 화면을 공유 중인지. */
  sharing: boolean;
  /** 누군가(로컬 포함) 화면을 공유 중인지. 스테이지 오버레이 표시 여부. */
  active: boolean;
  /** 다른 참가자가 공유 중이라 내가 시작할 수 없는지. 컨트롤 버튼을 비활성화한다. */
  blocked: boolean;
  /** 지금 공유 중인 참가자 identity. 표시 이름을 참가자 목록에서 찾을 때 쓴다. */
  activeIdentity: string | null;
  /** 활성 공유 트랙을 붙일 video 요소 콜백 ref. 공유가 없으면 아무 것도 붙지 않는다. */
  attachScreen: (element: HTMLVideoElement | null) => void;
  /** 공유 토글: 내가 공유 중이면 중지, 아니면 시작(다른 사람이 공유 중이면 무시). */
  toggle: () => void;
};

/**
 * 화면 공유를 제어한다(선택 요구사항: 세션당 활성 공유 1명, 역할 제한 없음).
 *
 * <p>순서가 핵심이다. 시작할 때 서버 슬롯을 먼저 확보(claim)한 뒤에만 LiveKit 트랙을 publish 한다 — 반대로 하면 두 사람이 동시에
 * 켠 화면이 잠깐 함께 방송된다. "누가 공유 중인가"는 LiveKit 트랙 이벤트로 그대로 드러나므로, 다른 참가자의 공유는 서버에 묻지 않고
 * LiveKit 상태에서 읽어 버튼을 잠근다.
 *
 * <p>정리: 중지·퇴장 시 슬롯을 명시적으로 반납하고, 크래시·강제 종료는 서버 TTL 이 회수한다. 공유 중에는 주기적으로 슬롯을 갱신해
 * TTL 을 늘리고, 갱신이 거부되면(다른 사람이 가져갔거나 세션 종료) 로컬 공유를 내려 상태를 일치시킨다.
 */
export function useScreenShare(
  sessionId: string,
  options: UseScreenShareOptions = {},
): ScreenShareState {
  const { claim = claimScreenShare, release = releaseScreenShare, refreshMs = CLAIM_REFRESH_MS } = options;
  const { room } = useRoomConnection();
  const { accessToken } = useAuth();

  const [snapshot, setSnapshot] = useState<ScreenShareSnapshot>(IDLE);

  const localIdentity = room?.localParticipant?.identity ?? null;
  const blocked = snapshot.activeIdentity !== null && snapshot.activeIdentity !== localIdentity;

  // 활성 공유 트랙을 붙일 video 요소와 지금 붙여 둔 트랙. 남의 요소를 떼거나 중복으로 붙이지 않으려고 기억한다.
  const elementRef = useRef<HTMLVideoElement | null>(null);
  const attachedRef = useRef<VideoTrack | null>(null);

  const sync = useCallback(() => {
    const sharer = activeSharerOf(room);
    const track = sharer ? screenTrackOf(sharer) : undefined;
    const element = elementRef.current;

    if (attachedRef.current && (attachedRef.current !== track || element === null)) {
      attachedRef.current.detach();
      attachedRef.current = null;
    }
    if (element !== null && track !== undefined && attachedRef.current !== track) {
      track.attach(element);
      attachedRef.current = track;
    }

    setSnapshot({
      sharing: room?.localParticipant?.isScreenShareEnabled ?? false,
      activeIdentity: sharer?.identity ?? null,
    });
  }, [room]);

  // ref 콜백은 identity 가 고정이라 최신 sync 를 클로저로 잡을 수 없다. 참조로 넘겨 최신 것을 부른다.
  const syncRef = useRef(sync);
  useEffect(() => {
    syncRef.current = sync;
  });

  const attachScreen = useCallback((element: HTMLVideoElement | null) => {
    elementRef.current = element;
    syncRef.current();
  }, []);

  useEffect(() => {
    // 초기 동기화를 effect 본문 밖(지연)으로 빼서 렌더-이펙트 동기 setState를 피한다.
    const initial = setTimeout(sync, 0);
    if (!room) {
      return () => clearTimeout(initial);
    }
    SCREEN_EVENTS.forEach((event) => room.on(event, sync));
    return () => {
      clearTimeout(initial);
      SCREEN_EVENTS.forEach((event) => room.off(event, sync));
      if (attachedRef.current) {
        attachedRef.current.detach();
        attachedRef.current = null;
      }
    };
  }, [room, sync]);

  // 시작·종료 요청이 겹쳐 트랙 상태가 뒤집히지 않도록 진행 중에는 무시한다.
  const inFlight = useRef(false);

  const startShare = useCallback(async () => {
    const local = room?.localParticipant;
    if (!local || accessToken === null || inFlight.current || blocked) {
      return;
    }
    inFlight.current = true;
    try {
      // 슬롯을 먼저 확보한다. 실패(409·기타)면 트랙을 켜지 않는다 — LiveKit 상태가 곧 공유자를 드러내 버튼이 잠긴다.
      await claim(sessionId, accessToken);
    } catch {
      inFlight.current = false;
      return;
    }
    try {
      await local.setScreenShareEnabled(true);
    } catch {
      // 사용자가 화면 선택을 취소했거나 publish 가 실패했다. 잡아 둔 슬롯을 반납해 다음 사람이 공유할 수 있게 한다.
      void release(sessionId, accessToken).catch(() => {});
    } finally {
      inFlight.current = false;
    }
  }, [room, accessToken, blocked, sessionId, claim, release]);

  const stopShare = useCallback(async () => {
    const local = room?.localParticipant;
    if (!local) {
      return;
    }
    try {
      await local.setScreenShareEnabled(false);
    } catch {
      // 트랙 정리 실패는 삼킨다. 슬롯은 아래에서 반납하고, 남은 트랙은 재연결·퇴장으로 정리된다.
    }
    if (accessToken !== null) {
      void release(sessionId, accessToken).catch(() => {});
    }
  }, [room, accessToken, sessionId, release]);

  const toggle = useCallback(() => {
    if (snapshot.sharing) {
      void stopShare();
    } else {
      void startShare();
    }
  }, [snapshot.sharing, startShare, stopShare]);

  // 공유 중에는 슬롯 TTL 을 주기적으로 갱신한다. 갱신이 거부되면 슬롯을 잃은 것이라 로컬 공유를 내린다.
  useEffect(() => {
    if (!snapshot.sharing || accessToken === null) {
      return;
    }
    const timer = setInterval(() => {
      void claim(sessionId, accessToken).catch((failure: unknown) => {
        if (failure instanceof ScreenShareRequestError) {
          void room?.localParticipant?.setScreenShareEnabled(false).catch(() => {});
        }
      });
    }, refreshMs);
    return () => clearInterval(timer);
  }, [snapshot.sharing, accessToken, sessionId, claim, refreshMs, room]);

  // 언마운트(퇴장) 시 공유 중이었다면 슬롯을 반납한다. room.disconnect 가 트랙은 자동 unpublish 한다.
  // 언마운트 콜백은 최신 값을 클로저로 잡을 수 없어, 렌더가 아닌 effect 에서 참조를 갱신해 둔다.
  const sharingRef = useRef(snapshot.sharing);
  const tokenRef = useRef(accessToken);
  const releaseRef = useRef(release);
  useEffect(() => {
    sharingRef.current = snapshot.sharing;
    tokenRef.current = accessToken;
    releaseRef.current = release;
  });
  useEffect(
    () => () => {
      if (sharingRef.current && tokenRef.current !== null) {
        void releaseRef.current(sessionId, tokenRef.current).catch(() => {});
      }
    },
    [sessionId],
  );

  return {
    sharing: snapshot.sharing,
    active: snapshot.activeIdentity !== null,
    blocked,
    activeIdentity: snapshot.activeIdentity,
    attachScreen,
    toggle,
  };
}
