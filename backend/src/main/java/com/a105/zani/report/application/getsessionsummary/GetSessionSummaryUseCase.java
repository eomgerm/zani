package com.a105.zani.report.application.getsessionsummary;

/**
 * 종료된 수업의 공통 요약을 돌려준다.
 *
 * <p>역할을 가리지 않는다. 수업 요약은 강사와 학생이 <b>같은 문장</b>을 보는 공통 산출물이다(FRD §21 — 공통 녹화·전사와 같은 성격). 막아야 할 것은 비참여자이고, 그 판정은
 * {@code ResolveEndedSessionParticipantUseCase} 가 멤버십 → 세션 존재 → 종료 순서로 내린다.
 *
 * <p>{@link com.a105.zani.report.application.listsessionsections.ListSessionSectionsUseCase} 와 달리 이 유스케이스는 권한을 직접 본다.
 * 그쪽은 이미 접근을 판정한 다른 도메인이 부르지만, 이쪽은 HTTP 경계가 바로 부르기 때문이다.
 */
public interface GetSessionSummaryUseCase {

    GetSessionSummaryResult get(GetSessionSummaryQuery query);
}
