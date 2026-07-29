package com.a105.zani.audioclip.application.port;

/**
 * Egress 가 강사 오디오를 밀어 넣을 수신 주소를 알려주는 포트.
 *
 * <p>주소에는 접속 자격이 함께 담긴다. 발급하는 쪽과 검증하는 쪽이 같은 프로세스라 자격은 기동 시 만들어 메모리에만 두며, 설정으로 관리하지 않는다.
 *
 * <p>반환값에 자격이 포함되므로 로그·응답·예외 메시지에 남기지 않는다.
 */
public interface AudioStreamEndpointPort {

    String streamUrlFor(long sessionId);
}
