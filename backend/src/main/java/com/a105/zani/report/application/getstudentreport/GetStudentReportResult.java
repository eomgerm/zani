package com.a105.zani.report.application.getstudentreport;

import java.util.List;

public record GetStudentReportResult(
        Activity activity, String participationSummary, List<Recommendation> recommendations) {

    /**
     * 본인 활동 집계.
     *
     * @param questionCount 모델이 판단한 질문 수. 값이 없으면 {@code null} 이다 — 0 으로 낮추지 않는다
     */
    public record Activity(long publicChatCount, long confusedCount, long missedCount, Integer questionCount) {}

    public record Recommendation(
            String recommendationType,
            String title,
            String description,
            long startSeconds,
            long endSeconds,
            int priority) {}
}
