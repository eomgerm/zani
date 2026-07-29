"use client";

import {
  analysisAvailabilityOf,
  useAttentionDetection,
  type CameraAvailability,
} from "@/domains/attention";
import { useLocalCameraVideo } from "../../useLocalCameraVideo";
import { AnalysisStatusNotice } from "./AnalysisStatusNotice";

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
 *
 * 가용 상태 배지(76)도 같은 이유로 이 안에서 렌더한다. 상태를 부모로 끌어올리면 격리가 깨진다.
 */
export function AttentionCameraSource({ active, denied = false }: AttentionCameraSourceProps) {
  // LiveKit이 이미 열어 둔 로컬 카메라 트랙을 읽는다(카메라를 두 번 열지 않는다).
  const { videoRef, attached } = useLocalCameraVideo();
  // 트랙이 요소에 붙기 전에는 읽을 프레임이 없다. 연결 불가와 판정 UNMEASURABLE 은 다른 개념이다.
  const camera: CameraAvailability = denied ? "denied" : active && attached ? "on" : "off";

  const { status } = useAttentionDetection({ videoRef, camera });

  return (
    <>
      {/*
        display:none 이면 브라우저가 프레임 갱신을 멈춰 판정이 불가능하므로, 화면에서 감추되
        렌더는 유지한다. 프레임은 이 기기 안에서만 쓰이고 서버로 나가지 않는다.
      */}
      <video
        ref={videoRef}
        data-testid="attention-camera-source"
        aria-hidden
        muted
        playsInline
        autoPlay
        className="pointer-events-none absolute size-px opacity-0"
      />
      <AnalysisStatusNotice availability={analysisAvailabilityOf(status)} />
    </>
  );
}
