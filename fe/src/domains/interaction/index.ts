// interaction 도메인의 공개 API. 다른 도메인/app 에서는 이 파일을 통해서만 접근한다.
//
// 채팅·손들기·반응이 함께 쓰는 STOMP 채널이 여기 있다. 미디어(오디오·비디오·화면)는 LiveKit 소관이라
// lecture 도메인이 다룬다.
export { SessionChannelProvider, useSessionChannel } from "./presentation/SessionChannelProvider";
export type {
  SessionChannelContextValue,
  SessionChannelProviderProps,
} from "./presentation/SessionChannelProvider";
export { CHAT_SEND_TIMEOUT_MS, useSessionChat } from "./presentation/useSessionChat";
export type {
  UseSessionChatOptions,
  UseSessionChatResult,
} from "./presentation/useSessionChat";
export type { ChatEntryStatus, ChatMessageView } from "./presentation/chatMessages";
export { useRaisedHands } from "./presentation/useRaisedHands";
export type {
  UseRaisedHandsOptions,
  UseRaisedHandsResult,
} from "./presentation/useRaisedHands";
export { REACTION_FLOAT_MS, useSessionReactions } from "./presentation/useSessionReactions";
export type {
  FloatingReaction,
  UseSessionReactionsOptions,
  UseSessionReactionsResult,
} from "./presentation/useSessionReactions";
export { REACTION_EMOJI, REACTION_KINDS } from "./presentation/reactions";
export type { ReactionKind } from "./presentation/reactions";
export type { SessionChannelState } from "./infrastructure/sessionChannel";
export type { LiveStateSnapshot } from "./infrastructure/liveStateApi";
