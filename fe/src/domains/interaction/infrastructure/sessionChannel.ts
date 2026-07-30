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
  /**
   * 연결할 때마다 호출해 **그 시점의** 액세스 토큰을 받는다. 값을 미리 받아두지 않는 이유가 중요하다 —
   * 자동 재연결은 채널을 만든 지 한참 뒤에 일어날 수 있고, 그동안 토큰이 갱신된다. 생성 시점 값을
   * 굳혀두면 갱신 이후의 재연결이 만료된 토큰을 보내 거절당하고 채팅이 죽은 채로 남는다.
   */
  readonly getAccessToken: () => string | null;
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
 * 개발 서버에서 붙을 백엔드 오리진. `next.config.ts` 의 rewrite destination 과 같은 포트다.
 *
 * 여기에 값을 박는 이유: `NEXT_PUBLIC_*` 를 새로 만들면 Dockerfile·compose·Jenkins 세 곳에 함께
 * 실어야 하고, 하나만 빠지면 로컬은 되고 배포만 조용히 죽는다(`NEXT_PUBLIC_GOOGLE_CLIENT_ID`
 * 가 실제로 그렇게 빠져 배포 로그인이 깨진 적이 있다). 배포는 이미 연결돼 있는
 * `NEXT_PUBLIC_API_BASE_URL` 을 그대로 쓴다.
 */
const DEV_BACKEND_ORIGIN = "http://localhost:8080";

export interface SessionChannelUrlOptions {
  readonly apiBaseUrl: string | undefined;
  /** 브라우저가 보고 있는 출처. 배포에서 API 주소가 비어 있을 때의 기준이다. */
  readonly origin: string;
  readonly isDevelopment: boolean;
}

/**
 * 핸드셰이크 URL. API 주소의 스킴만 ws 로 바꾼다.
 *
 * <b>개발 모드에서 API 주소가 비어 있으면 백엔드로 직접 붙는다.</b> 그 구성은 Next rewrite 로 `/api`
 * 를 넘기는 방식인데, rewrite 는 WebSocket 업그레이드를 프록시하지 않아 같은 출처로 붙으면 `/ws` 를
 * 받아 줄 대상이 없다. 이건 STOMP 냐 raw WebSocket 이냐와 무관한 전송 계층 문제다.
 *
 * 배포에서 같은 도메인을 쓰려면 Nginx 에 `/ws` location 이 필요하다(`Upgrade`·`Connection` 헤더와
 * 긴 `proxy_read_timeout`). `/api` 블록은 일반 프록시라 업그레이드를 넘기지 않는다.
 */
export function sessionChannelUrl(options: SessionChannelUrlOptions): string {
  const configured = (options.apiBaseUrl ?? "").replace(/\/$/, "");
  const base = configured || (options.isDevelopment ? DEV_BACKEND_ORIGIN : options.origin);
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
  getAccessToken,
  handlers,
}) => {
  const client = new Client({
    brokerURL: sessionChannelUrl({
      apiBaseUrl: process.env.NEXT_PUBLIC_API_BASE_URL,
      origin: typeof window === "undefined" ? "" : window.location.origin,
      isDevelopment: process.env.NODE_ENV === "development",
    }),
    reconnectDelay: RECONNECT_DELAY_MS,
    // 첫 연결과 모든 자동 재연결 직전에 불린다. 여기서 헤더를 다시 채워야 갱신된 토큰이 실린다.
    beforeConnect: () => {
      const token = getAccessToken();
      client.connectHeaders = token === null ? {} : { Authorization: `Bearer ${token}` };
    },
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
