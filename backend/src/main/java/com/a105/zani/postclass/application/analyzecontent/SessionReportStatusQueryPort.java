package com.a105.zani.postclass.application.analyzecontent;

/**
 * 공통 분석이 이미 적재됐는지 읽는 교차 모듈 조회 계약.
 *
 * <p>{@code postclass} 가 소유하고 인프라가 네이티브 SQL 로 구현한다. 남의 <b>테이블</b>을 읽지 남의 <b>자바 타입</b>을 import 하지 않는다(ARCH-006).
 */
public interface SessionReportStatusQueryPort {

    /** 멱등의 바깥 겹이다 — true 면 LLM 호출도 일어나지 않는다. */
    boolean hasSessionReport(Long sessionId);
}
