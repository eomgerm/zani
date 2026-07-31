package com.a105.zani.session.domain.model;

/**
 * 참가자를 클라이언트에 노출할 때 쓰는 식별자. LiveKit identity 와 업무 이벤트 봉투의 발신자 식별자가 같은 값이어야 프론트가 두 목록(LiveKit 참가자 · 채팅·손들기 이벤트)을 이어 붙일 수
 * 있다.
 *
 * <p>회원 ID 나 이메일이 아니라 참가자 ID 를 쓴다. 세션 밖으로 나가면 의미가 없는 값이라, 이벤트 봉투에 실려도 개인을 식별하지 못한다.
 */
public final class SessionParticipantIdentity {

    /** 숫자만 있으면 다른 ID 와 구분되지 않아 접두사를 붙인다. */
    private static final String PREFIX = "p-";

    private SessionParticipantIdentity() {}

    public static String of(Long participantId) {
        return PREFIX + participantId;
    }
}
