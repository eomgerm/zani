package com.a105.zani.report.application.getstudentreport;

import java.util.List;

public record GetStudentReportResult(
        Activity activity, String participationSummary, List<Recommendation> recommendations) {

    public record Activity(long publicChatCount, long confusedCount, long missedCount) {}

    public record Recommendation(
            String recommendationType,
            String title,
            String description,
            long startSeconds,
            long endSeconds,
            int priority) {}
}
