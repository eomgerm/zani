package com.a105.zani.report.application.getstudentreport;

import java.util.List;

public record StudentReportView(
        long publicChatCount,
        long confusedCount,
        long missedCount,
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
