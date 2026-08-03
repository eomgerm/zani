package com.a105.zani.report.application.listsessionsections;

import java.util.List;

/**
 * 저장된 내용 구간을 읽는다.
 *
 * <p>내용 구간은 집계해서 쓰는 읽기 전용 투영이라 Aggregate 저장 계약({@code Repository})이 아니라 Application 이 소유한 QueryPort 로 읽는다(DDD 가이드
 * ARCH-010). 서비스가 Spring Data 리포지터리를 직접 들면 Application 이 Infrastructure 를 향해 의존하게 되고, 조회 수단을 바꾸는 순간 유스케이스까지 함께 열어야 한다.
 */
public interface ListSessionSectionsQueryPort {

    /** 한 세션의 내용 구간을 시작 오프셋 오름차순으로 읽는다. 248 이 아직 채우지 않았으면 빈 목록이다. */
    List<SessionSectionView> findBySessionId(long sessionId);
}
