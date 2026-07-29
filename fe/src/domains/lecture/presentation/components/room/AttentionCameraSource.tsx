"use client";

import { useAttentionDetection, type CameraAvailability } from "@/domains/attention";
import { useLocalCameraTrack } from "../../useLocalCameraTrack";

export interface AttentionCameraSourceProps {
  /** 판정을 돌려도 되는 상태인지. 보통 `media.ready && media.cameraEnabled` 를 넘긴다. */
  readonly active: boolean;
  /** 카메라 프레임을 얻을 권한이 없는 상태인지(학생이 스스로 끈 것과 구분한다). */
  readonly denied?: boolean;
}

/**
 * 참여도 판정에 쓸 로컬 카메라 프레임 소스. 판정 배선을 이 잎 컴포넌트가 소유한다.
 *
 * 판정 세션은 100ms 마다 상태를 보고하므로, 이 구독을 강의실 화면에 두면 참가자 타일 전체가
 * 그 주기로 다시 렌더된다. 상태 변화를 여기 안에 가둬 두려고 컴포넌트로 분리했다.
 * 판정 결과를 화면에 표시하는 일은 별도 티켓(76) 소관이라, 지금은 판정을 돌리는 것까지만 한다.
 */
export function AttentionCameraSource({ active, denied = false }: AttentionCameraSourceProps) {
  // LiveKit이 이미 열어 둔 로컬 카메라 트랙을 읽는다(카메라를 두 번 열지 않는다).
  const { track } = useLocalCameraTrack();
  // 트랙이 publish 되기 전에는 읽을 프레임이 없다. 연결 불가와 판정 UNMEASURABLE 은 다른 개념이다.
  const camera: CameraAvailability = denied ? "denied" : active && track !== null ? "on" : "off";

  useAttentionDetection({ camera, track });

  /*
    프레임은 Worker 가 `MediaStreamTrackProcessor` 로 트랙에서 직접 읽으므로 트랙을 붙일
    video 요소가 필요 없다. 그래서 아무것도 그리지 않는다. 프레임은 이 기기 안에서만 쓰이고
    서버로 나가지 않는다.
  */
  return null;
}
