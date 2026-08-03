package com.a105.zani.report.application.savestudentanalysis;

import java.util.List;

/**
 * 다른 도메인이 만든 분석 결과를 리포트로 저장해 달라는 요청.
 *
 * <p>추천 유형을 문자열로 받는다. 호출 도메인이 {@code report} 의 enum 을 import 하지 않아도 되게 하려는 것이고, 미정의 값은 이 도메인이 거절한다.
 */
public record SaveStudentAnalysisCommand(
        Long sessionId, Long sessionParticipantId, String participationSummary, List<Recommendation> recommendations) {

    public record Recommendation(
            String type, String title, String description, long startedOffsetMs, long endedOffsetMs) {}
}
