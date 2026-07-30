package com.a105.zani.session.application.port;

public interface SessionEventPublishPort {

    /** 세션의 모든 구독자에게 업무 이벤트를 보낸다. */
    void publishToSession(long sessionId, SessionEvent event);

    /**
     * 전송을 거절했다고 보낸 사람에게만 알린다.
     *
     * @param memberId 인증 주체 이름(STOMP Principal 의 {@code getName()}) — 사용자 목적지 해석에 쓴다
     */
    void publishRejection(String memberId, SessionEventRejection rejection);
}
