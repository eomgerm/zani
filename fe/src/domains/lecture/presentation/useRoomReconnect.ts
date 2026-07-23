"use client";

import { useEffect, useRef, useState } from "react";

import { useRoomConnection, type RoomConnectionState } from "./RoomProvider";

/**
 * 측정 가능 여부. 학습 신호 분석(attention 도메인, 스토리 16)이 소비한다.
 * `connected`가 아닌 모든 구간은 신호를 측정할 수 없으므로 `UNMEASURABLE`이다.
 */
export type Measurability = "MEASURABLE" | "UNMEASURABLE";

/**
 * 재연결 상태.
 * - `stable`: 연결됨
 * - `reconnecting`: 연결 진행/재연결 중(LiveKit 자동 재연결)
 * - `rejoining`: 연결 실패 후 재입장(재토큰) 시도 중
 * - `failed`: 재입장 시도 한도 초과
 */
export type ReconnectStatus = "stable" | "reconnecting" | "rejoining" | "failed";

export type UseRoomReconnectOptions = {
  /** 자동 재입장 최대 시도 횟수. 기본 3. */
  maxRejoinAttempts?: number;
  /** 재입장 시도 사이 대기(ms). 기본 2000. */
  rejoinDelayMs?: number;
  /**
   * 측정 가능 여부가 바뀔 때 통지한다. attention 도메인이 이 신호를 받아
   * `UNMEASURABLE` 구간을 확인 후보 시간에서 제외한다(스토리 16). 기본 no-op.
   */
  onMeasurabilityChange?: (measurability: Measurability) => void;
};

export type RoomReconnectState = {
  connectionState: RoomConnectionState;
  status: ReconnectStatus;
  measurability: Measurability;
  rejoinAttempts: number;
};

/**
 * RoomProvider의 연결 상태 위에 자동 재입장과 측정 가능 여부 통지를 얹는 훅.
 * RoomProvider의 이벤트 리스너를 중복 구독하지 않고 `useRoomConnection`만 소비한다.
 * 노출 값은 순수 파생이며, 상태 변경은 지연 콜백(타이머) 안에서만 일어난다.
 */
export function useRoomReconnect(
  options: UseRoomReconnectOptions = {},
): RoomReconnectState {
  const { maxRejoinAttempts = 3, rejoinDelayMs = 2000, onMeasurabilityChange } = options;
  const { connectionState, retry } = useRoomConnection();

  const [rejoinAttempts, setRejoinAttempts] = useState(0);

  const measurability: Measurability =
    connectionState === "connected" ? "MEASURABLE" : "UNMEASURABLE";

  const status: ReconnectStatus =
    connectionState === "connected"
      ? "stable"
      : connectionState === "connecting"
        ? "reconnecting"
        : rejoinAttempts >= maxRejoinAttempts
          ? "failed"
          : "rejoining";

  // 측정 가능 여부가 실제로 바뀔 때만 통지한다.
  const prevMeasurabilityRef = useRef<Measurability | null>(null);
  useEffect(() => {
    if (prevMeasurabilityRef.current === measurability) return;
    prevMeasurabilityRef.current = measurability;
    onMeasurabilityChange?.(measurability);
  }, [measurability, onMeasurabilityChange]);

  // 재연결에 성공하면 다음 단절을 위해 시도 횟수를 되돌린다(지연 setState).
  useEffect(() => {
    if (connectionState !== "connected" || rejoinAttempts === 0) return;
    const id = setTimeout(() => setRejoinAttempts(0), 0);
    return () => clearTimeout(id);
  }, [connectionState, rejoinAttempts]);

  // 연결 실패 시 한도 안에서 자동 재입장을 시도한다.
  useEffect(() => {
    if (connectionState !== "error" || rejoinAttempts >= maxRejoinAttempts) return;
    const id = setTimeout(() => {
      setRejoinAttempts((attempts) => attempts + 1);
      retry();
    }, rejoinDelayMs);
    return () => clearTimeout(id);
  }, [connectionState, rejoinAttempts, maxRejoinAttempts, rejoinDelayMs, retry]);

  return { connectionState, status, measurability, rejoinAttempts };
}
