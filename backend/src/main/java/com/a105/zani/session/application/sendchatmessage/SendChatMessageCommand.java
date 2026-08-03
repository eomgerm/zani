package com.a105.zani.session.application.sendchatmessage;

/**
 * 공개 채팅 전송 입력.
 *
 * @param userId 인증 주체(STOMP CONNECT 에서 확인된 회원 ID)
 * @param clientEventId 클라이언트가 만든 전송 식별자. 재시도 멱등과 낙관적 UI 대조에 쓴다.
 */
public record SendChatMessageCommand(Long sessionId, Long userId, String clientEventId, String content) {}
