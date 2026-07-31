package com.a105.zani.session.application.port;

/**
 * 반응 연타를 막는다.
 *
 * <p>클라이언트에서만 막으면 소용이 없다 — 소켓에 직접 프레임을 밀어 넣으면 전 참가자 화면이 이모지로 덮인다. 게다가 반응은 한 건이 세션 전체로 퍼지므로, 한 사람의 연타가 참가자 수만큼 증폭된다.
 */
public interface ReactionRateLimitPort {

    /** @return 이번 반응을 보내도 되면 true, 아직 간격이 차지 않았으면 false */
    boolean tryAcquire(long sessionId, String identity);
}
