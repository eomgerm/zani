package com.a105.zani.report.application.getstudentreport;

import java.util.List;

/**
 * 조회 어댑터가 조립해 주는 읽기 모델.
 *
 * @param questionCount 모델이 판단한 질문 수. 분석이 값을 내지 못했으면 {@code null} 이며 0 이 아니다. 세 집계와 달리 서버가 세는 값이 아니라 저장된 판정이다
 */
public record StudentReportView(
        long publicChatCount,
        long confusedCount,
        long missedCount,
        Integer questionCount,
        String participationSummary,
        List<Recommendation> recommendations) {

    public record Recommendation(
            String recommendationType,
            String title,
            String description,
            long startSeconds,
            long endSeconds,
            int priority) {}
}
