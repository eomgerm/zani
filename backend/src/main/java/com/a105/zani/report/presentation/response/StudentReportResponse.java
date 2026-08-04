package com.a105.zani.report.presentation.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.report.application.getstudentreport.GetStudentReportResult;

@Schema(description = "종료된 수업의 학생 본인 학습 리포트")
public record StudentReportResponse(
        Activity activity, String participationSummary, List<Recommendation> recommendations) {

    public record Activity(long publicChatCount, long confusedCount, long missedCount) {}

    public record Recommendation(
            String recommendationType,
            String title,
            String description,
            long startSeconds,
            long endSeconds,
            int priority) {

        private static Recommendation from(GetStudentReportResult.Recommendation recommendation) {
            return new Recommendation(
                    recommendation.recommendationType(),
                    recommendation.title(),
                    recommendation.description(),
                    recommendation.startSeconds(),
                    recommendation.endSeconds(),
                    recommendation.priority());
        }
    }

    public static StudentReportResponse from(GetStudentReportResult result) {
        return new StudentReportResponse(
                new Activity(
                        result.activity().publicChatCount(),
                        result.activity().confusedCount(),
                        result.activity().missedCount()),
                result.participationSummary(),
                result.recommendations().stream().map(Recommendation::from).toList());
    }
}
