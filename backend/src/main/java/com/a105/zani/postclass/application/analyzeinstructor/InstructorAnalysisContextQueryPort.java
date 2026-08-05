package com.a105.zani.postclass.application.analyzeinstructor;

import java.util.Optional;

/**
 * {@code session}·{@code report}·{@code attention}·{@code coach} 도메인의 테이블을 읽는 교차 모듈 조회 계약.
 *
 * <p>{@code postclass} 가 소유하고 인프라가 네이티브 SQL 로 구현한다. 남의 <b>테이블</b>을 읽지 남의 <b>자바 타입</b>을 import 하지 않는다(ARCH-006).
 */
public interface InstructorAnalysisContextQueryPort {

    /** 멱등의 바깥 겹이다 — true 면 LLM 호출도 일어나지 않는다. */
    boolean hasReport(Long sessionId);

    /** 공통 분석(수업 요약)이 있는 세션의 입력 전부. 요약이 없거나 삭제된 세션이면 빈 값. */
    Optional<InstructorAnalysisContext> findContext(Long sessionId);
}
