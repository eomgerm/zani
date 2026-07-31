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
     * 손을 든 것으로 기록한다. 이미 들고 있었다면 순번은 그대로 유지한다.
     *
     * @param raisedAtMillis 순번의 기준. 서버가 받은 시각이라 클라이언트 시계와 무관하다
     */
    RaisedHandChange raise(long sessionId, String identity, long raisedAtMillis);

    RaisedHandChange lower(long sessionId, String identity);

    /**
     * 든 순서대로. 아무도 없으면 빈 목록.
     *
     * <p>쓰기와 달리 읽기는 실패를 구분하지 않는다. 못 읽으면 목록이 비어 보일 뿐이라 화면이 사실과 다른 것을 주장하지 않는다 — 손을 들지도 않았는데 들었다고 알리는 쓰기 쪽과 다르다.
     */
    List<String> raisedInOrder(long sessionId);
}
