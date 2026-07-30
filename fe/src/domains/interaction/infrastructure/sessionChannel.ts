import { Client } from "@stomp/stompjs";

import {
  parseSessionEvent,
  parseSessionEventRejection,
  type SessionEventEnvelope,
  type SessionEventRejection,
} from "./sessionEvent";

/**
 * 업무 이벤트 STOMP 채널.
 *
 * LiveKit 과 역할이 다르다 — 미디어는 LiveKit 이 나르고, 채팅·손들기·반응은 이 채널을 지난다.
 * LiveKit DataPacket 은 쓰지 않는다.
 */

export type SessionChannelState = "connecting" | "connected" | "error";

export interface SessionChannelHandlers {
  onEvent: (event: SessionEventEnvelope) => void;
  /** 내 전송이 거절됐을 때. `clientEventId` 로 어느 전송인지 알 수 있다. */
  onRejection: (rejection: SessionEventRejection) => void;
  onStateChange: (state: SessionChannelState) => void;
}

export interface SessionChannel {
  publishChat: (clientEventId: string, content: string) => void;
  deactivate: () => void;
}

export interface SessionChannelOptions {
  readonly sessionId: string;
  readonly accessToken: string;
  readonly handlers: SessionChannelHandlers;
}

export type SessionChannelFactory = (options: SessionChannelOptions) => SessionChannel;

/** 서버가 STOMP 프레임을 받는 경로(백엔드 `SessionChannelDestinations`). */
const HANDSHAKE_PATH = "/ws";
const ERROR_QUEUE = "/user/queue/errors";

const RECONNECT_DELAY_MS = 3_000;

const sessionTopic = (sessionId: string) => `/topic/sessions/${sessionId}`;
const chatDestination = (sessionId: string) => `/app/sessions/${sessionId}/chat`;

/**
 * 핸드셰이크 URL. API 주소를 그대로 쓰되 스킴만 ws 로 바꾼다.
 *
 * `NEXT_PUBLIC_API_BASE_URL` 이 비어 있으면 같은 출처로 본다 — 로컬에서 프록시를 쓰는 구성이다.
 * 이 값은 빌드 시점에 박히므로 런타임에 바꿀 수 없다.
 */
export function sessionChannelUrl(apiBaseUrl: string | undefined, origin: string): string {
  const base = (apiBaseUrl ?? "").replace(/\/$/, "") || origin;
  return `${base.replace(/^http/, "ws")}${HANDSHAKE_PATH}`;
}

/**
 * 실제 STOMP 클라이언트를 만든다.
 *
 * 토큰은 CONNECT 프레임 헤더로 보낸다. 브라우저 WebSocket 은 핸드셰이크에 `Authorization` 을
 * 붙일 수 없고, 흔한 우회책인 쿼리 파라미터는 액세스 토큰을 프록시·액세스 로그에 남긴다.
 * STOMP 는 핸드셰이크와 별개로 CONNECT 프레임에 헤더를 실을 수 있어 URL 에 노출되지 않는다.
 */
export const createSessionChannel: SessionChannelFactory = ({
  sessionId,
  accessToken,
  handlers,
}) => {
  const client = new Client({
    brokerURL: sessionChannelUrl(
      process.env.NEXT_PUBLIC_API_BASE_URL,
      typeof window === "undefined" ? "" : window.location.origin,
    ),
    connectHeaders: { Authorization: `Bearer ${accessToken}` },
    reconnectDelay: RECONNECT_DELAY_MS,
    onConnect: () => {
      client.subscribe(sessionTopic(sessionId), (message) => {
        const event = parseSessionEvent(message.body);
        // 읽을 수 없는 프레임은 버린다. 모르는 종류(64~66 선배포)일 수도 있어 오류로 다루지 않는다.
        if (event !== null) handlers.onEvent(event);
      });
      client.subscribe(ERROR_QUEUE, (message) => {
        const rejection = parseSessionEventRejection(message.body);
        if (rejection !== null) handlers.onRejection(rejection);
      });
      handlers.onStateChange("connected");
    },
    // 끊기면 라이브러리가 스스로 다시 붙는다. 끊긴 동안은 연결 중으로 보여 준다.
    onWebSocketClose: () => handlers.onStateChange("connecting"),
    // 인증 실패·구독 거절은 ERROR 프레임으로 온다. 자동 재시도로 풀리지 않으므로 오류로 굳힌다.
    onStompError: () => {
      handlers.onStateChange("error");
      client.deactivate();
    },
  });

  client.activate();

  return {
    publishChat: (clientEventId, content) => {
      client.publish({
        destination: chatDestination(sessionId),
        body: JSON.stringify({ clientEventId, content }),
      });
    },
    deactivate: () => {
      void client.deactivate();
    },
  };
};
