package com.a105.zani.report.application.saveinstructoranalysis;

import java.util.List;

/**
 * 다른 도메인이 만든 강사 분석 결과를 리포트로 저장해 달라는 요청.
 *
 * <p>평가 분야를 문자열로 받는다. 호출 도메인이 {@code report} 의 enum 을 import 하지 않아도 되게 하려는 것이고, 미정의 값은 이 도메인이 거절한다.
 */
public record SaveInstructorAnalysisCommand(
        Long sessionId, String overallFeedback, int questionCount, List<Score> scores, List<Insight> insights) {

    public record Score(String evaluationType, int score) {}

    /**
     * @param evidence 이 인사이트의 근거. {@code instructor_report_insights.content} 로 들어간다
     * @param startedOffsetMs 대상 구간 시작. 전체 수업 대상이면 {@code endedOffsetMs} 와 함께 null 이다
     */
    public record Insight(String title, String evidence, String suggestion, Long startedOffsetMs, Long endedOffsetMs) {}
}
