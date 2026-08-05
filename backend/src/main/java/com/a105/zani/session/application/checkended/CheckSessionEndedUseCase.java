package com.a105.zani.session.application.checkended;

import java.util.Collection;
import java.util.Set;

/** 다른 도메인에 세션 엔티티를 노출하지 않고 종료 여부만 제공하는 공개 조회 유스케이스. */
public interface CheckSessionEndedUseCase {

    /** 세션이 존재하고 종료된 경우에만 {@code true}를 반환한다. */
    boolean isEnded(Long sessionId);

    /** 주어진 세션 중 종료된 세션 ID만 한 번의 배치 조회로 반환한다. */
    Set<Long> endedSessionIds(Collection<Long> sessionIds);
}
