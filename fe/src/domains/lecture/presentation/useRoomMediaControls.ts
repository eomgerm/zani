"use client";

import { useCallback, useEffect, useRef, useState } from "react";
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

/**
 * livekit.TrackSource 와이어 값. livekit-client가 이 enum을 재노출하지 않고 `@livekit/protocol` 은 직접 의존이 아니라
 * 값으로 판정한다. 가이드 §8에 따라 역할별 publish 제한은 canPublish가 아니라 canPublishSources로 표현된다.
 */
const TRACK_SOURCE_CAMERA = 1;
const TRACK_SOURCE_MICROPHONE = 2;

const TOGGLE_FAILURE_MESSAGE = "장치를 사용할 수 없습니다. 연결과 권한을 확인해 주세요.";
const SWITCH_FAILURE_MESSAGE = "선택한 장치로 바꾸지 못했습니다. 다른 장치를 선택해 주세요.";

export type RoomMediaControls = {
  /** 로컬 마이크가 publish 중인지. */
  microphoneEnabled: boolean;
  /** 로컬 카메라가 publish 중인지. */
  cameraEnabled: boolean;
  /** room에 연결돼 조작할 수 있는 상태인지. false면 제어 UI를 비활성화한다. */
  ready: boolean;
  /** 강사 제한 모드: 서버가 마이크 publish 권한을 회수한 상태. */
  microphoneBlocked: boolean;
  /** 강사 제한 모드: 서버가 카메라 publish 권한을 회수한 상태. */
  cameraBlocked: boolean;
  /** 장치 조작 실패 안내. 다음 성공 시 null로 돌아간다. */
  mediaError: string | null;
  /** 선택 가능한 마이크 목록. */
  microphones: readonly SelectOption[];
  /** 선택 가능한 카메라 목록. */
  cameras: readonly SelectOption[];
  /** 사용 중인 마이크·카메라 deviceId. LiveKit이 아직 알려주지 않으면 마지막으로 요청한 값을 쓴다. */
  activeMicrophoneId: string | null;
  activeCameraId: string | null;
  toggleMicrophone: () => void;
  toggleCamera: () => void;
  selectMicrophone: (deviceId: string) => void;
  selectCamera: (deviceId: string) => void;
};

/** LiveKit ParticipantPermission 중 publish 판정에 쓰는 부분만 본다(@livekit/protocol 직접 의존 회피). */
type PublishPermissions = {
  canPublish: boolean;
  canPublishSources?: readonly number[];
};

type MediaSnapshot = Pick<
  RoomMediaControls,
  | "microphoneEnabled"
  | "cameraEnabled"
  | "microphoneBlocked"
  | "cameraBlocked"
  | "activeMicrophoneId"
  | "activeCameraId"
> & { connected: boolean };

type DeviceOptions = Pick<RoomMediaControls, "microphones" | "cameras">;

const disconnectedSnapshot: MediaSnapshot = {
  microphoneEnabled: false,
  cameraEnabled: false,
  connected: false,
  microphoneBlocked: false,
  cameraBlocked: false,
  activeMicrophoneId: null,
  activeCameraId: null,
};

const noDeviceOptions: DeviceOptions = { microphones: [], cameras: [] };

/**
 * 서버가 해당 source의 publish를 막았는지 본다. canPublish가 false면 전부 차단이고,
 * canPublishSources가 비어 있으면 source 제한이 없는 상태로 본다(가이드 §8).
 */
function sourceBlocked(permissions: PublishPermissions | undefined, source: number): boolean {
  if (!permissions) {
    return false;
  }
  if (!permissions.canPublish) {
    return true;
  }
  const allowed = permissions.canPublishSources ?? [];
  return allowed.length > 0 && !allowed.includes(source);
}

/** 라벨은 권한 허용 후에야 채워지므로, 비어 있으면 입장 전 점검과 같은 문구로 채운다. */
function toOptions(devices: MediaDeviceInfo[], kind: MediaDeviceKind): SelectOption[] {
  const fallbackPrefix = kind === CAMERA_KIND ? "카메라" : "마이크";
  return devices
    .filter((device) => device.kind === kind && device.deviceId !== "")
    .map((device, index) => ({
      value: device.deviceId,
      label: device.label || `${fallbackPrefix} ${index + 1}`,
    }));
}

function snapshot(room: Room | null): MediaSnapshot {
  const local = room?.localParticipant;
  if (!room || !local) {
    return disconnectedSnapshot;
  }
  const permissions: PublishPermissions | undefined = local.permissions;
  return {
    microphoneEnabled: local.isMicrophoneEnabled,
    cameraEnabled: local.isCameraEnabled,
    connected: true,
    microphoneBlocked: sourceBlocked(permissions, TRACK_SOURCE_MICROPHONE),
    cameraBlocked: sourceBlocked(permissions, TRACK_SOURCE_CAMERA),
    activeMicrophoneId: room.getActiveDevice(MICROPHONE_KIND) ?? null,
    activeCameraId: room.getActiveDevice(CAMERA_KIND) ?? null,
  };
}

/**
 * RoomProvider가 제공하는 실제 LiveKit room의 로컬 트랙 publish와 입력 장치를 제어한다.
 * 화면 상태는 localParticipant를 그대로 스냅샷하므로, 서버·다른 기기에서 트랙이 바뀌어도 같은 값을 보여준다.
 * 재연결 중에는 조작을 막고, 언마운트/room 교체 시 리스너와 진행 중인 요청 결과를 모두 버린다.
 *
 * @param prejoinInviteCode 입장 전 점검에서 고른 장치를 이어 쓰기 위한 초대 코드. 저장값이 없으면 기본 장치로 진행한다.
 */
export function useRoomMediaControls(prejoinInviteCode?: string): RoomMediaControls {
  const { room, connectionState } = useRoomConnection();
  const [state, setState] = useState<MediaSnapshot>(disconnectedSnapshot);
  const [devices, setDevices] = useState<DeviceOptions>(noDeviceOptions);
  const [requestedMicrophoneId, setRequestedMicrophoneId] = useState<string | null>(null);
  const [requestedCameraId, setRequestedCameraId] = useState<string | null>(null);
  const [mediaError, setMediaError] = useState<string | null>(null);
  // 진행 중인 요청이 끝난 뒤에도 같은 room인지 확인해, 이미 끊긴 room의 상태를 되살리지 않는다.
  const currentRoom = useRef<Room | null>(null);
  // publish 요청이 끝나기 전 연속 클릭은 무시한다(트랙 상태가 아직 반영되지 않아 반대로 뒤집힌다).
  const inFlight = useRef(new Set<"microphone" | "camera">());

  useEffect(() => {
    currentRoom.current = room;

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
      if (!local || inFlight.current.has(kind)) {
        return;
      }
      const permissions: PublishPermissions | undefined = local.permissions;
      const source = kind === "microphone" ? TRACK_SOURCE_MICROPHONE : TRACK_SOURCE_CAMERA;
      if (sourceBlocked(permissions, source)) {
        return;
      }

      inFlight.current.add(kind);
      const applied =
        kind === "microphone"
          ? local.setMicrophoneEnabled(!local.isMicrophoneEnabled)
          : local.setCameraEnabled(!local.isCameraEnabled);
      void Promise.resolve(applied)
        .then(
          () => {
            if (currentRoom.current !== room) {
              return;
            }
            setMediaError(null);
            setState(snapshot(room));
          },
          () => {
            if (currentRoom.current === room) {
              setMediaError(TOGGLE_FAILURE_MESSAGE);
            }
          },
        )
        .finally(() => inFlight.current.delete(kind));
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
      if (kind === MICROPHONE_KIND) {
        setRequestedMicrophoneId(deviceId);
      } else if (kind === CAMERA_KIND) {
        setRequestedCameraId(deviceId);
      }
      // switchActiveDevice는 실패를 예외가 아니라 false로 알린다(요청 장치를 열지 못해 폴백된 경우).
      void Promise.resolve(room.switchActiveDevice(kind, deviceId)).then(
        (switched) => {
          if (currentRoom.current !== room) {
            return;
          }
          if (switched === false) {
            setMediaError(SWITCH_FAILURE_MESSAGE);
            return;
          }
          setMediaError(null);
          setState(snapshot(room));
        },
        () => {
          if (currentRoom.current === room) {
            setMediaError(SWITCH_FAILURE_MESSAGE);
          }
        },
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
    if (!media || typeof media.addEventListener !== "function") {
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
        // 목록 조회 실패는 마지막 목록을 그대로 둔다(수업 중에 선택 메뉴가 사라지지 않게).
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
    // 전환 요청이 setState를 부르므로 effect 본문 밖(지연)에서 실행한다.
    const applyPrejoinDevices = setTimeout(() => {
      if (prejoin.microphoneDeviceId) {
        switchDevice(MICROPHONE_KIND, prejoin.microphoneDeviceId);
      }
      if (prejoin.cameraDeviceId) {
        switchDevice(CAMERA_KIND, prejoin.cameraDeviceId);
      }
    }, 0);
    return () => clearTimeout(applyPrejoinDevices);
  }, [room, prejoinInviteCode, switchDevice]);

  return {
    microphoneEnabled: state.microphoneEnabled,
    cameraEnabled: state.cameraEnabled,
    // 재연결 중에는 room 객체가 남아 있어도 조작을 막는다(네트워크 문제를 장치 오류로 알리지 않게).
    ready: state.connected && connectionState === "connected",
    microphoneBlocked: state.microphoneBlocked,
    cameraBlocked: state.cameraBlocked,
    mediaError,
    ...devices,
    activeMicrophoneId: state.activeMicrophoneId ?? requestedMicrophoneId,
    activeCameraId: state.activeCameraId ?? requestedCameraId,
    toggleMicrophone,
    toggleCamera,
    selectMicrophone,
    selectCamera,
  };
}
