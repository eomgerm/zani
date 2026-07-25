package com.a105.zani.session.application.port;

/**
 * 세션의 미디어 room 정보를 다른 도메인에 공개하는 세션 도메인의 application port. room 이름 규칙과 미디어 서버 설정은 세션이 단일 소유하며, 녹화 등 다른 도메인은 이 port만 통해
 * 접근한다(다른 도메인의 infrastructure를 직접 참조하지 않는다).
 */
public interface MediaRoomPort {

    /** 세션에 대응하는 미디어 room 이름. 서버가 규칙으로 재구성한다. */
    String roomName(Long sessionId);

    /** 미디어 서버 접속 자격증명. */
    MediaServerCredentials credentials();
}
