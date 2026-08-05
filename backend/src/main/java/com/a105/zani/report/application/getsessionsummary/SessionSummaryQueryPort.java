package com.a105.zani.report.application.getsessionsummary;

import java.util.Optional;

/**
 * 게시된 수업 요약을 읽는다.
 *
 * <p>한 컬럼을 읽을 뿐이지만 Aggregate 저장 계약({@code Repository})이 아니라 Application 이 소유한 QueryPort 로 둔다. 소유를 가르는 것은 SQL 의 복잡도가 아니라
 * 반환 목적이고(DDD 가이드 ARCH-010), 이 값은 화면에 그대로 나가는 투영이다.
 */
public interface SessionSummaryQueryPort {

    /** 게시된 요약만 돌려준다. 세션에 요약이 없거나 아직 게시 전이면 비어 있다. */
    Optional<String> findPublishedSummaryBySessionId(long sessionId);
}
