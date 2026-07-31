package com.a105.zani.session.application.port;

import java.util.List;

/**
 * 지금 손을 든 참가자 목록. <b>서버가 받은 순서</b>를 그대로 유지한다.
 *
 * <p>이력({@code interaction_events})과 역할이 다르다. 이쪽은 화면이 지금 보여줄 현재 상태이고, 이력은 리포트가 나중에 읽는다. 한곳에 합치면 실시간 조회가 DB 를 때리거나, 반대로
 * 이력이 TTL 로 사라진다.
 */
public interface RaisedHandQueuePort {

    /**
     * 손을 든 것으로 기록한다.
     *
     * @param raisedAtMillis 순번의 기준. 서버가 받은 시각이라 클라이언트 시계와 무관하다
     * @return 처음 든 경우 true. 이미 들고 있었으면 false 이며 순번은 그대로 유지된다
     */
    boolean raise(long sessionId, String identity, long raisedAtMillis);

    /** @return 실제로 내린 경우 true. 이미 내려가 있었으면 false */
    boolean lower(long sessionId, String identity);

    /** 든 순서대로. 아무도 없으면 빈 목록. */
    List<String> raisedInOrder(long sessionId);
}
