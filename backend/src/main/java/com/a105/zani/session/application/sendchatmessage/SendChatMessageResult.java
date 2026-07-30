package com.a105.zani.session.application.sendchatmessage;

/**
 * @param eventId 저장된 메시지의 식별자. 재시도로 걸러진 경우에는 처음 저장 때 부여된 값이다.
 * @param duplicate 같은 {@code clientEventId} 가 이미 처리돼 저장·브로드캐스트를 건너뛴 경우 true
 */
public record SendChatMessageResult(String eventId, boolean duplicate) {}
