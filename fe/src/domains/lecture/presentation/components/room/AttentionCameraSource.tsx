"use client";

import { useEffect, useRef } from "react";

import {
  analysisAvailabilityOf,
  useAttentionDetection,
  useAttentionEventReporter,
  type AnalysisAvailability,
  type CameraAvailability,
  type DetectorOutput,
} from "@/domains/attention";
import { useLocalCameraTrack } from "../../useLocalCameraTrack";

export interface AttentionCameraSourceProps {
  /** 10초 관측을 보낼 수업. 전송 경로가 세션별이라 판정만으로는 어디로 보낼지 알 수 없다. */
  readonly sessionId: string;
  /** 판정을 돌려도 되는 상태인지. 보통 `media.ready && media.cameraEnabled` 를 넘긴다. */
  readonly active: boolean;
  /** 카메라 프레임을 얻을 권한이 없는 상태인지(학생이 스스로 끈 것과 구분한다). */
  readonly denied?: boolean;
  /**
   * 분석 가용 상태가 바뀔 때 알린다(티켓 76).
   *
   * 판정 상태 자체가 아니라 접힌 가용 상태만 올린다. 판정 상태는 표본마다 흔들리지만 가용
   * 상태는 카메라가 꺼지거나 검출기가 죽을 때만 바뀌므로, 상위 화면이 그때만 다시 그린다.
   */
  onAvailabilityChange?: (availability: AnalysisAvailability) => void;
  /** 10초 창의 로컬 판정 결과를 학생 프롬프트 판정으로 올린다. */
  onDetection?: (output: DetectorOutput) => void;
}

/**
 * 참여도 판정에 쓸 로컬 카메라 프레임 소스. 판정 배선을 이 잎 컴포넌트가 소유한다.
 *
 * 판정 세션은 100ms 마다 상태를 보고하므로, 이 구독을 강의실 화면에 두면 참가자 타일 전체가
 * 그 주기로 다시 렌더된다. 상태 변화를 여기 안에 가둬 두려고 컴포넌트로 분리했다.
 *
 * 10초 관측은 여기서 서버로 나간다. 로컬 판정 출력(확률 포함)은 상위 코칭 파이프라인에만
 * 전달하며, 이 컴포넌트는 화면을 렌더하지 않는다.
 */
export function AttentionCameraSource({
  sessionId,
  active,
  denied = false,
  onAvailabilityChange,
  onDetection,
}: AttentionCameraSourceProps) {
  // LiveKit이 이미 열어 둔 로컬 카메라 트랙을 읽는다(카메라를 두 번 열지 않는다).
  const { track } = useLocalCameraTrack();
  // 트랙이 publish 되기 전에는 읽을 프레임이 없다. 연결 불가와 판정 UNMEASURABLE 은 다른 개념이다.
  const camera: CameraAvailability = denied ? "denied" : active && track !== null ? "on" : "off";

  // 판정이 도는 동안 10초마다 관측 1건이 서버에 도달해야 한다. 상태가 바뀌지 않아도 계속 보낸다 —
  // 끊기면 만료로 드러나야 서버가 "브라우저가 죽음"과 "학생이 이탈함"을 구분할 수 있다.
  const reportToServer = useAttentionEventReporter({ sessionId });

  const { status } = useAttentionDetection({ camera, track, onDetection, onReport: reportToServer });
  const availability = analysisAvailabilityOf(status);

  // 콜백 identity 가 바뀌어도 다시 알리지 않도록 ref 로 미러링한다.
  const notifyRef = useRef(onAvailabilityChange);
  useEffect(() => {
    notifyRef.current = onAvailabilityChange;
  });

  useEffect(() => {
    notifyRef.current?.(availability);
  }, [availability]);

  /*
    분석 전용 clone의 processor stream을 Worker가 읽으므로 트랙을 붙일 video 요소가 필요
    없다. 그래서 아무것도 그리지 않는다. 프레임은 이 기기 안에서만 쓰이고 서버로 나가지
    않는다. 가용 상태 배지도 여기서 그리지 않고 상위 화면이 상단 바에 놓는다 — 띄워 얹으면
    수업 조작을 가린다.
  */
  return null;
}
