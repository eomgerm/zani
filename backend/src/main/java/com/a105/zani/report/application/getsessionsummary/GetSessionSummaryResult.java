package com.a105.zani.report.application.getsessionsummary;

/**
 * 수업 요약 한 건.
 *
 * <p>강사·학생이 같은 값을 받는다. 여기에 역할별 필드를 더하지 않는다 — 갈라야 할 내용이 생기면 그것은 공통 요약이 아니라 각자의 리포트({@code instructor_reports}
 * ·{@code student_reports})가 맡을 몫이다.
 *
 * @param summary 사후 공통 분석이 만든 수업 요약. 게시된 값만 여기까지 온다
 */
public record GetSessionSummaryResult(String summary) {}
