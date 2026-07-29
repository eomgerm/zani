"use client";

import { useCallback } from "react";

import { useAuth } from "@/domains/auth";
import {
  attentionEventTypeOf,
  reportAttentionEvent,
  useAttentionDetection,
  type AttentionEventReporter,
  type AttentionPrediction,
  type AttentionWindow,
  type CameraAvailability,
} from "@/domains/attention";
import { useLocalCameraVideo } from "../../useLocalCameraVideo";

export interface AttentionCameraSourceProps {
  /** 판정을 돌려도 되는 상태인지. 보통 `media.ready && media.cameraEnabled` 를 넘긴다. */
  readonly active: boolean;
  /** 카메라 프레임을 얻을 권한이 없는 상태인지(학생이 스스로 끈 것과 구분한다). */
  readonly denied?: boolean;
  /** 판정을 보고할 세션. 없으면 판정만 돌리고 보고하지 않는다. */
  readonly sessionId?: string;
  /** 테스트에서 API 경계를 대체하기 위한 주입점. */
  readonly reportEvent?: AttentionEventReporter;
}

/**
 * 참여도 판정에 쓸 로컬 카메라 프레임 소스. 판정 배선을 이 잎 컴포넌트가 소유한다.
 *
 * 판정 세션은 100ms 마다 상태를 보고하므로, 이 구독을 강의실 화면에 두면 참가자 타일 전체가
 * 그 주기로 다시 렌더된다. 상태 변화를 여기 안에 가둬 두려고 컴포넌트로 분리했다.
 * 판정 결과를 화면에 표시하는 일은 별도 티켓(76) 소관이라, 지금은 판정을 돌리는 것까지만 한다.
 */
export function AttentionCameraSource({
  active,
  denied = false,
  sessionId,
  reportEvent = reportAttentionEvent,
}: AttentionCameraSourceProps) {
  const { accessToken } = useAuth();
  // LiveKit이 이미 열어 둔 로컬 카메라 트랙을 읽는다(카메라를 두 번 열지 않는다).
  const { videoRef, attached } = useLocalCameraVideo();
  // 트랙이 요소에 붙기 전에는 읽을 프레임이 없다. 연결 불가와 판정 UNMEASURABLE 은 다른 개념이다.
  const camera: CameraAvailability = denied ? "denied" : active && attached ? "on" : "off";

  // 판정 1건을 서버에 보고한다. 실패는 삼킨다 — 보고가 안 되는 것으로 수업을 끊지 않는다.
  const onPrediction = useCallback(
    (prediction: AttentionPrediction, window: AttentionWindow | null) => {
      const type = attentionEventTypeOf(prediction.label);
      // 창 메타가 없으면 보고하지 않는다 — signalQuality 를 지어내면 서버가 측정 품질을 잘못 계산한다.
      if (type === null || window === null || sessionId === undefined || accessToken === null) {
        return;
      }
      const endedAt = new Date();
      const startedAt = new Date(endedAt.getTime() - window.durationSec * 1_000);
      void reportEvent(
        sessionId,
        {
          type,
          startedAt: startedAt.toISOString(),
          endedAt: endedAt.toISOString(),
          durationSec: window.durationSec,
          signalQuality: window.signalQuality,
          clientEventId: crypto.randomUUID(),
        },
        accessToken,
      ).catch((error: unknown) => {
        console.warn("[attention] 판정 보고 실패", error);
      });
    },
    [accessToken, reportEvent, sessionId],
  );

  useAttentionDetection({ videoRef, camera, onPrediction });

  return (
    /*
      display:none 이면 브라우저가 프레임 갱신을 멈춰 판정이 불가능하므로, 화면에서 감추되
      렌더는 유지한다. 프레임은 이 기기 안에서만 쓰이고 서버로 나가지 않는다.
    */
    <video
      ref={videoRef}
      data-testid="attention-camera-source"
      aria-hidden
      muted
      playsInline
      autoPlay
      className="pointer-events-none absolute size-px opacity-0"
    />
  );
}
