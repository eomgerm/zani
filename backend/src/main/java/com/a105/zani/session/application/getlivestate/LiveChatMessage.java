package com.a105.zani.session.application.getlivestate;

/**
 * 스냅샷에 실리는 공개 채팅 한 건.
 *
 * <p>발신자는 이름이 아니라 {@code senderIdentity} 로만 담는다. 이름은 참가자 디렉터리에 한 번만 두고 여기서는 참조한다 — 200 건에 같은 이름을 반복해 실을 이유가 없다.
 *
 * @param eventId 실시간 스트림의 {@code eventId} 와 같은 값. 스냅샷과 스트림이 겹칠 때 중복을 걸러내는 기준이다.
 */
public record LiveChatMessage(String eventId, String senderIdentity, long occurredOffsetMs, String content) {}
