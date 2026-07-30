package com.a105.zani.session.application.port;

import java.time.Instant;

import com.a105.zani.session.domain.model.SessionStatus;

/**
 * 세션 상태 전이 이력을 남기는 포트.
 *
 * <p>세션 행은 <b>현재</b> 상태만 들고 있어서, 수업이 끝난 뒤에는 언제 시작했고 언제 정리에 들어갔는지가 남지 않는다. 리포트가 수업 타임라인을 다시 세울 때 필요한 근거라 전이가 일어난 순간에만
 * 기록한다.
 *
 * <p>전이가 실제로 일어났을 때만 호출한다. 멱등하게 무시된 요청까지 기록하면 이력이 중복 요청 횟수로 오염된다.
 */
public interface SessionStatusHistoryPort {

    /**
     * 상태 전이 한 건을 기록한다.
     *
     * @param from 이전 상태. 최초 생성이면 null
     */
    void record(long sessionId, SessionStatus from, SessionStatus to, Instant changedAt);
}
