import type { LiveStateSnapshot } from "../infrastructure/liveStateApi";
import { chatContentOf, type SessionEventEnvelope } from "../infrastructure/sessionEvent";

/**
 * 채팅 목록 상태. 순수 함수라 훅 없이 단독으로 검증한다.
 *
 * 세 갈래가 한 목록으로 합쳐진다 — 재연결 스냅샷(이력), 실시간 스트림, 그리고 아직 서버 확인을
 * 받지 못한 내 전송(낙관적 표시). 겹침은 `eventId`, 내 전송의 확정은 `clientEventId` 로 맞춘다.
 */

export type ChatEntryStatus = "sending" | "sent" | "failed";

export interface ChatEntry {
  /** 확정된 메시지는 `eventId`, 아직 보내는 중이면 `clientEventId`. */
  readonly id: string;
  readonly clientEventId: string | null;
  /**
   * 서버가 확정한 발신자 identity. 낙관적으로 그려 둔 내 전송은 아직 null 이다.
   *
   * "내 메시지인지"를 여기 담지 않는 이유: LiveKit 연결이 스냅샷보다 늦으면 내 identity 를 모르는
   * 채로 이력을 받는다. 그때 판정을 굳혀 두면 내 옛 메시지가 남의 것으로 보이고 스스로 고쳐지지
   * 않는다. 판정은 화면을 그릴 때 한다.
   */
  readonly senderIdentity: string | null;
  readonly authorName: string;
  readonly isInstructor: boolean;
  readonly content: string;
  readonly status: ChatEntryStatus;
  /** 거절 사유 코드. 실패가 아니면 null. */
  readonly failureReason: string | null;
}

interface DirectoryEntry {
  readonly displayName: string;
  readonly isInstructor: boolean;
}

export interface ChatState {
  /** identity → 표시 정보. 이미 퇴장한 참가자도 들어 있다(스냅샷이 함께 내려준다). */
  readonly directory: Readonly<Record<string, DirectoryEntry>>;
  readonly entries: readonly ChatEntry[];
}

export type ChatAction =
  | { readonly type: "snapshot"; readonly snapshot: LiveStateSnapshot }
  | { readonly type: "received"; readonly event: SessionEventEnvelope }
  | {
      readonly type: "sending";
      readonly clientEventId: string;
      readonly content: string;
      readonly authorName: string;
      readonly isInstructor: boolean;
    }
  | { readonly type: "failed"; readonly clientEventId: string; readonly reason: string }
  | { readonly type: "retrying"; readonly clientEventId: string };

export const initialChatState: ChatState = { directory: {}, entries: [] };

const UNKNOWN_AUTHOR = "알 수 없음";

function directoryOf(snapshot: LiveStateSnapshot): Record<string, DirectoryEntry> {
  const directory: Record<string, DirectoryEntry> = {};
  for (const participant of snapshot.participants) {
    directory[participant.identity] = {
      displayName: participant.displayName,
      isInstructor: participant.role === "INSTRUCTOR",
    };
  }
  return directory;
}

export function chatReducer(state: ChatState, action: ChatAction): ChatState {
  switch (action.type) {
    case "snapshot": {
      const directory = { ...state.directory, ...directoryOf(action.snapshot) };
      const history: ChatEntry[] = action.snapshot.chatMessages.map((message) => {
        const sender = directory[message.senderIdentity];
        return {
          id: message.eventId,
          clientEventId: null,
          senderIdentity: message.senderIdentity,
          authorName: sender?.displayName ?? UNKNOWN_AUTHOR,
          isInstructor: sender?.isInstructor ?? false,
          content: message.content,
          status: "sent",
          failureReason: null,
        };
      });

      // 스냅샷이 실시간 스트림보다 늦게 도착할 수 있다(구독 먼저, 스냅샷 나중). 그 사이 받은
      // 메시지가 스냅샷에도 있으면 겹치므로 eventId 로 걸러내고 이력을 앞에 놓는다.
      const historyIds = new Set(history.map((entry) => entry.id));
      const afterHistory = state.entries.filter((entry) => !historyIds.has(entry.id));
      return { directory, entries: [...history, ...afterHistory] };
    }

    case "received": {
      const content = chatContentOf(action.event);
      if (content === null) return state; // 본문 없는 채팅 이벤트는 그릴 것이 없다.

      const { eventId, clientEventId, sender } = action.event;
      if (state.entries.some((entry) => entry.id === eventId)) {
        return state; // 이미 있다(스냅샷과 겹쳤거나 프레임이 두 번 왔다).
      }

      const directory: Record<string, DirectoryEntry> = {
        ...state.directory,
        [sender.identity]: {
          displayName: sender.displayName,
          isInstructor: sender.role === "INSTRUCTOR",
        },
      };
      const confirmed: ChatEntry = {
        id: eventId,
        clientEventId,
        senderIdentity: sender.identity,
        authorName: sender.displayName,
        isInstructor: sender.role === "INSTRUCTOR",
        content,
        status: "sent",
        failureReason: null,
      };

      // 내가 보낸 것이면 낙관적으로 그려 둔 자리를 그대로 확정한다. 새로 붙이면 같은 메시지가 두 번 보인다.
      const pendingIndex =
        clientEventId === null
          ? -1
          : state.entries.findIndex(
              (entry) => entry.clientEventId === clientEventId && entry.status !== "sent",
            );
      if (pendingIndex === -1) {
        return { directory, entries: [...state.entries, confirmed] };
      }
      const entries = [...state.entries];
      entries[pendingIndex] = confirmed;
      return { directory, entries };
    }

    case "sending": {
      const optimistic: ChatEntry = {
        id: action.clientEventId,
        clientEventId: action.clientEventId,
        senderIdentity: null,
        authorName: action.authorName,
        isInstructor: action.isInstructor,
        content: action.content,
        status: "sending",
        failureReason: null,
      };
      return { ...state, entries: [...state.entries, optimistic] };
    }

    case "failed":
      return {
        ...state,
        entries: state.entries.map((entry) =>
          entry.clientEventId === action.clientEventId && entry.status === "sending"
            ? { ...entry, status: "failed", failureReason: action.reason }
            : entry,
        ),
      };

    case "retrying":
      return {
        ...state,
        entries: state.entries.map((entry) =>
          entry.clientEventId === action.clientEventId && entry.status === "failed"
            ? { ...entry, status: "sending", failureReason: null }
            : entry,
        ),
      };
  }
}

/** 화면이 그리는 형태. `mine` 은 저장하지 않고 지금 아는 내 identity 로 매번 판정한다. */
export interface ChatMessageView extends ChatEntry {
  readonly mine: boolean;
}

export function chatMessageViews(
  entries: readonly ChatEntry[],
  myIdentity: string | null,
): ChatMessageView[] {
  return entries.map((entry) => ({
    ...entry,
    // 아직 확정되지 않은 항목은 내가 보낸 것뿐이다.
    mine: entry.senderIdentity === null || entry.senderIdentity === myIdentity,
  }));
}
