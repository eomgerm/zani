package com.a105.zani.report.presentation.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.report.application.getstudentreport.GetStudentReportResult;

@Schema(description = "종료된 수업의 학생 본인 학습 리포트")
public record StudentReportResponse(
        Activity activity, String participationSummary, List<Recommendation> recommendations) {

    /**
     * 본인 활동 집계. 다른 학생과 견주는 값이 아니며 하나의 점수로 합치지 않는다(REPORT-S-010).
     *
     * <p>앞의 셋은 서버가 행을 센 값이라 항상 있다. {@code questionCount} 만 모델이 판단한 저장 값이라 비어 있을 수 있다 — 공개 채팅에는 질문만 있지 않아서 행을 셀 수 없다.
     */
    public record Activity(
            @Schema(description = "공개 채팅으로 남긴 발화 수", example = "4")
            long publicChatCount,

            @Schema(description = "확인 프롬프트에 '헷갈려요' 로 답한 횟수", example = "1")
            long confusedCount,

            @Schema(description = "확인 프롬프트에 '놓쳤어요' 로 답한 횟수", example = "0")
            long missedCount,

            @Schema(
                    description = "AI 가 공개 채팅에서 질문인 발화만 세어 판단한 질문 수."
                            + " 분석이 값을 내지 못했으면 null 이며 0 이 아니다 — 0 은 질문을 안 했다는 뜻이다.",
                    example = "2",
                    nullable = true)
            Integer questionCount) {}

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
                        result.activity().missedCount(),
                        result.activity().questionCount()),
                result.participationSummary(),
                result.recommendations().stream().map(Recommendation::from).toList());
    }
}
