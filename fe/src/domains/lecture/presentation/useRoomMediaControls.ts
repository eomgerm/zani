"use client";

import { useCallback, useEffect, useState } from "react";
import { RoomEvent } from "livekit-client";
import type { Room } from "livekit-client";

import { readPrejoinResult } from "@/features/media/prejoinResult";
import type { SelectOption } from "@/shared/ui";
import { useRoomConnection } from "./RoomProvider";

/** 로컬 publish 상태가 바뀔 수 있는 LiveKit 이벤트(가이드 §7). */
const MEDIA_EVENTS: RoomEvent[] = [
  RoomEvent.LocalTrackPublished,
  RoomEvent.LocalTrackUnpublished,
  RoomEvent.TrackMuted,
  RoomEvent.TrackUnmuted,
  RoomEvent.ActiveDeviceChanged,
  RoomEvent.ParticipantPermissionsChanged,
];

const MICROPHONE_KIND = "audioinput";
const CAMERA_KIND = "videoinput";

const TOGGLE_FAILURE_MESSAGE = "장치를 사용할 수 없습니다. 연결과 권한을 확인해 주세요.";
const SWITCH_FAILURE_MESSAGE = "선택한 장치로 바꾸지 못했습니다. 다른 장치를 선택해 주세요.";

export type RoomMediaControls = {
  /** 로컬 마이크가 publish 중인지. */
  microphoneEnabled: boolean;
  /** 로컬 카메라가 publish 중인지. */
  cameraEnabled: boolean;
  /** room에 연결돼 조작할 수 있는 상태인지. false면 제어 UI를 비활성화한다. */
  ready: boolean;
  /** 강사 제한 모드: 서버가 publish 권한을 회수해 마이크·카메라를 켤 수 없는 상태. */
  publishBlocked: boolean;
  /** 장치 조작 실패 안내. 다음 성공 시 null로 돌아간다. */
  mediaError: string | null;
  /** 선택 가능한 마이크 목록. */
  microphones: readonly SelectOption[];
  /** 선택 가능한 카메라 목록. */
  cameras: readonly SelectOption[];
  /** 실제로 사용 중인 마이크·카메라 deviceId. */
  activeMicrophoneId: string | null;
  activeCameraId: string | null;
  toggleMicrophone: () => void;
  toggleCamera: () => void;
  selectMicrophone: (deviceId: string) => void;
  selectCamera: (deviceId: string) => void;
};

type MediaSnapshot = Pick<
  RoomMediaControls,
  | "microphoneEnabled"
  | "cameraEnabled"
  | "ready"
  | "publishBlocked"
  | "activeMicrophoneId"
  | "activeCameraId"
>;

type DeviceOptions = Pick<RoomMediaControls, "microphones" | "cameras">;

const disconnectedSnapshot: MediaSnapshot = {
  microphoneEnabled: false,
  cameraEnabled: false,
  ready: false,
  publishBlocked: false,
  activeMicrophoneId: null,
  activeCameraId: null,
};

const noDeviceOptions: DeviceOptions = { microphones: [], cameras: [] };

/** 라벨은 권한 허용 후에야 채워지므로, 비어 있으면 deviceId 앞자리로 대체해 구분만 되게 한다. */
function toOptions(devices: MediaDeviceInfo[], kind: MediaDeviceKind): SelectOption[] {
  return devices
    .filter((device) => device.kind === kind && device.deviceId !== "")
    .map((device) => ({
      value: device.deviceId,
      label: device.label || `장치 ${device.deviceId.slice(0, 6)}`,
    }));
}

function snapshot(room: Room | null): MediaSnapshot {
  const local = room?.localParticipant;
  if (!local) {
    return disconnectedSnapshot;
  }
  return {
    microphoneEnabled: local.isMicrophoneEnabled,
    cameraEnabled: local.isCameraEnabled,
    ready: true,
    // permissions는 서버가 토큰·moderation으로 정한다. 값이 없으면 제한 없음으로 본다.
    publishBlocked: local.permissions ? !local.permissions.canPublish : false,
    activeMicrophoneId: room?.getActiveDevice(MICROPHONE_KIND) ?? null,
    activeCameraId: room?.getActiveDevice(CAMERA_KIND) ?? null,
  };
}

/**
 * RoomProvider가 제공하는 실제 LiveKit room의 로컬 트랙 publish와 입·출력 장치를 제어한다.
 * 화면 상태는 localParticipant를 그대로 스냅샷하므로, 서버·다른 기기에서 트랙이 바뀌어도 같은 값을 보여준다.
 * 언마운트/room 교체 시 리스너를 모두 해제한다.
 *
 * @param prejoinInviteCode 입장 전 점검에서 고른 장치를 이어 쓰기 위한 초대 코드. 저장값이 없으면 기본 장치로 진행한다.
 */
export function useRoomMediaControls(prejoinInviteCode?: string): RoomMediaControls {
  const { room } = useRoomConnection();
  const [state, setState] = useState<MediaSnapshot>(disconnectedSnapshot);
  const [devices, setDevices] = useState<DeviceOptions>(noDeviceOptions);
  const [mediaError, setMediaError] = useState<string | null>(null);

  useEffect(() => {
    const update = () => setState(snapshot(room));
    // 초기 동기화를 effect 본문 밖(지연)으로 빼서 렌더-이펙트 동기 setState를 피한다.
    const initial = setTimeout(update, 0);

    if (!room) {
      return () => clearTimeout(initial);
    }

    MEDIA_EVENTS.forEach((event) => room.on(event, update));
    return () => {
      clearTimeout(initial);
      MEDIA_EVENTS.forEach((event) => room.off(event, update));
    };
  }, [room]);

  const toggle = useCallback(
    (kind: "microphone" | "camera") => {
      const local = room?.localParticipant;
      if (!local || (local.permissions && !local.permissions.canPublish)) {
        return;
      }
      const applied =
        kind === "microphone"
          ? local.setMicrophoneEnabled(!local.isMicrophoneEnabled)
          : local.setCameraEnabled(!local.isCameraEnabled);
      void Promise.resolve(applied).then(
        () => {
          setMediaError(null);
          setState(snapshot(room));
        },
        () => setMediaError(TOGGLE_FAILURE_MESSAGE),
      );
    },
    [room],
  );

  const toggleMicrophone = useCallback(() => toggle("microphone"), [toggle]);
  const toggleCamera = useCallback(() => toggle("camera"), [toggle]);

  const switchDevice = useCallback(
    (kind: MediaDeviceKind, deviceId: string) => {
      if (!room) {
        return;
      }
      void Promise.resolve(room.switchActiveDevice(kind, deviceId)).then(
        () => {
          setMediaError(null);
          setState(snapshot(room));
        },
        () => setMediaError(SWITCH_FAILURE_MESSAGE),
      );
    },
    [room],
  );

  const selectMicrophone = useCallback(
    (deviceId: string) => switchDevice(MICROPHONE_KIND, deviceId),
    [switchDevice],
  );
  const selectCamera = useCallback(
    (deviceId: string) => switchDevice(CAMERA_KIND, deviceId),
    [switchDevice],
  );

  // 권한 허용 후에야 장치 라벨이 채워지므로, 마운트 시점과 devicechange 때 목록을 갱신한다.
  useEffect(() => {
    const media = typeof navigator === "undefined" ? undefined : navigator.mediaDevices;
    if (!media) {
      return;
    }
    let isCurrent = true;
    const refresh = async () => {
      try {
        const found = await media.enumerateDevices();
        if (!isCurrent) {
          return;
        }
        setDevices({
          microphones: toOptions(found, MICROPHONE_KIND),
          cameras: toOptions(found, CAMERA_KIND),
        });
      } catch {
        // 목록 조회 실패는 선택 UI를 비우는 것으로 충분하다(토글은 그대로 동작한다).
        if (isCurrent) {
          setDevices(noDeviceOptions);
        }
      }
    };

    void refresh();
    media.addEventListener("devicechange", refresh);
    return () => {
      isCurrent = false;
      media.removeEventListener("devicechange", refresh);
    };
  }, [room]);

  // 입장 전 점검에서 고른 장치를 강의실에서도 이어 쓴다. room 연결마다 한 번만 적용한다.
  useEffect(() => {
    if (!room || !prejoinInviteCode) {
      return;
    }
    const prejoin = readPrejoinResult(prejoinInviteCode);
    if (!prejoin) {
      return;
    }
    if (prejoin.microphoneDeviceId) {
      switchDevice(MICROPHONE_KIND, prejoin.microphoneDeviceId);
    }
    if (prejoin.cameraDeviceId) {
      switchDevice(CAMERA_KIND, prejoin.cameraDeviceId);
    }
  }, [room, prejoinInviteCode, switchDevice]);

  return {
    ...state,
    ...devices,
    mediaError,
    toggleMicrophone,
    toggleCamera,
    selectMicrophone,
    selectCamera,
  };
}
