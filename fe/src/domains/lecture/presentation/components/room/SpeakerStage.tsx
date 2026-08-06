"use client";

import { GridContainer, GridItem } from "@thangdevalone/meeting-grid-layout-react";
import { ParticipantTile, type ParticipantTileData } from "./ParticipantTile";
import { SNAP_SIZE, TILE_ASPECT_RATIO, TILE_FILL, TILE_GAP } from "./roomGridLayout";

/** 공유 화면이 있으면 배치기의 0번 칸이다. 참가자는 그 뒤로 이어진다. */
const SHARED_SCREEN_INDEX = 0;

/**
 * 발표자 보기에서 누구를 크게 띄울지 고른다.
 *
 * <p>순서가 규칙이다 — 공유 화면 → 말하는 사람 → 강사 → 목록의 첫 사람. 앞의 조건이 성립하면 뒤는
 * 보지 않는다. 공유 중에 발화자를 띄우면 정작 봐야 할 자료가 사라지고, 아무도 말하지 않을 때 화면이
 * 비면 무엇을 보라는 것인지 알 수 없어 마지막까지 후보를 둔다.
 *
 * <p><b>"말하는 사람" 은 마지막으로 말한 사람이다.</b> 지금 이 순간 소리를 내는 사람만 보면 말 사이의
 * 짧은 침묵마다 강사로 돌아갔다가 다시 넘어와 화면이 널뛴다(!126 리뷰에서 한 번 잡힌 문제다).
 * 그래서 직전 발화자가 목록에 남아 있는 한 그 자리를 지킨다.
 *
 * @param lastSpeakerId 직전에 발화가 잡힌 참가자. 아직 아무도 말하지 않았으면 `null`.
 * @returns 배치기에 넘길 참가자 순번. 참가자가 하나도 없고 공유도 없으면 `null`.
 */
export function spotlightIndex(
  participants: readonly ParticipantTileData[],
  sharing: boolean,
  lastSpeakerId: string | null = null,
): number | null {
  if (sharing) return SHARED_SCREEN_INDEX;

  const sticky = participants.findIndex((participant) => participant.id === lastSpeakerId);
  // 지금 무대에 선 사람이 아직 말하는 중이면 유지한다. 아래의 findIndex 는 배열 순서(로컬 우선)라,
  // 이 가드가 없으면 동시 발화 때 순서상 앞선 참가자가 말하던 사람의 자리를 뺏는다(!126 리뷰).
  if (sticky !== -1 && participants[sticky]?.speaking === true) return sticky;

  const speaking = participants.findIndex((participant) => participant.speaking);
  if (speaking !== -1) return speaking;

  // 아무도 말하지 않는다. 직전 발화자가 남아 있으면 그대로 둔다.
  if (sticky !== -1) return sticky;

  const instructor = participants.findIndex(
    (participant) => participant.role === "instructor",
  );
  if (instructor !== -1) return instructor;

  return participants.length > 0 ? 0 : null;
}

type SpeakerStageProps = {
  participants: ParticipantTileData[];
  /** 공유 화면 트랙을 붙일 video ref. 공유 중이 아니면 주지 않는다. */
  attachScreen?: React.Ref<HTMLVideoElement>;
  /** 공유 화면 왼쪽 위 칩에 넣을 문구. */
  sharerLabel?: string;
  /** 참가자별 카메라 video ref 를 만들어 주는 함수. 없으면 아바타만 보여준다. */
  videoRefFor?: (identity: string) => React.Ref<HTMLVideoElement>;
  /** 내 타일은 거울처럼 뒤집는다. */
  localParticipantId: string | null;
  /**
   * 직전에 발화가 잡힌 참가자.
   *
   * <p>이 컴포넌트가 스스로 기억하지 않는다 — 갤러리로 갔다 오면 언마운트되면서 기억이 사라져,
   * 돌아올 때마다 강사부터 다시 시작한다. 보기를 전환하는 쪽(`useLastSpeaker`)이 들고 있어야 한다.
   */
  lastSpeakerId?: string | null;
};

/**
 * 발표자 보기. 지금 봐야 할 하나만 화면 가득 띄운다.
 *
 * <p>배치기의 spotlight 모드라 나머지는 그리지 않는다 — 갤러리와 나누는 지점이 그것이다. 페이지도
 * 두지 않는다. 한 명만 보는 화면에서 페이지를 넘긴다는 것은 발표자를 손으로 고른다는 뜻이라, 발화에
 * 따라 저절로 바뀌는 이 화면의 성격과 어긋난다.
 */
export function SpeakerStage({
  participants,
  attachScreen,
  sharerLabel,
  videoRefFor,
  localParticipantId,
  lastSpeakerId = null,
}: SpeakerStageProps) {
  const sharing = attachScreen !== undefined;
  const offset = sharing ? 1 : 0;
  const subject = spotlightIndex(participants, sharing, lastSpeakerId);

  if (subject === null) {
    return (
      <div className="absolute inset-0 flex items-center justify-center text-[13px] text-panel-muted">
        강의자를 기다리고 있어요
      </div>
    );
  }

  return (
    <div className="absolute inset-0 p-3.5">
      <GridContainer
        role="group"
        aria-label="발표자 보기"
        count={participants.length + offset}
        aspectRatio={TILE_ASPECT_RATIO}
        gap={TILE_GAP}
        forceAspectRatio
        disableFloat
        layoutMode="spotlight"
        pinnedIndex={sharing ? SHARED_SCREEN_INDEX : subject + offset}
        springPreset="smooth"
      >
        {sharing && (
          <GridItem index={SHARED_SCREEN_INDEX} transition={SNAP_SIZE}>
            <div className="relative size-full overflow-hidden rounded-[14px] border border-[#1e2740] bg-[#0f1626]">
              <video
                ref={attachScreen}
                autoPlay
                muted
                playsInline
                data-testid="screen-share-video"
                className="size-full object-contain"
              />
              <div className="z-stage-chip absolute left-4 top-4 flex items-center gap-1.5 font-bold">
                <span className="size-2 rounded-full bg-primary" />
                {sharerLabel}
              </div>
            </div>
          </GridItem>
        )}

        {participants.map((participant, index) => (
          <GridItem key={participant.id} index={index + offset} transition={SNAP_SIZE}>
            <ParticipantTile
              fit={TILE_FILL}
              participant={participant}
              videoRef={videoRefFor?.(participant.id)}
              mirrored={participant.id === localParticipantId}
            />
          </GridItem>
        ))}
      </GridContainer>
    </div>
  );
}
