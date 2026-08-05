package com.a105.zani.quiz.presentation.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.quiz.application.submitquizanswers.SubmitQuizAnswersResult;

/** 제출 직후의 채점 결과. 이후 재조회(GET)는 같은 정보를 문항에 붙여 준다. */
@Schema(description = "퀴즈 채점 결과")
public record QuizGradingResponse(
        @Schema(description = "퀴즈 ID", example = "742891573920571390")
        Long quizId,

        @Schema(description = "전체 문항 수", example = "5") int totalCount,
        @Schema(description = "정답 문항 수", example = "3") int correctCount,
        @Schema(description = "문항별 채점(출제 순서)") List<QuestionGradingResponse> results) {

    public static QuizGradingResponse from(SubmitQuizAnswersResult result) {
        return new QuizGradingResponse(
                result.quizId(),
                result.totalCount(),
                result.correctCount(),
                result.results().stream().map(QuestionGradingResponse::from).toList());
    }

    @Schema(description = "문항 하나의 채점")
    public record QuestionGradingResponse(
            @Schema(description = "문항 ID", example = "742891573920571392")
            Long questionId,

            @Schema(description = "정답 여부") boolean correct,
            @Schema(description = "학생이 선택한 보기 ID") Long selectedOptionId,
            @Schema(description = "정답 보기 ID") Long correctOptionId,
            @Schema(description = "해설") String explanation,

            @Schema(description = "관련 강의 구간 다시 보기의 재생 위치(ms). 근거 구간을 특정하지 못한 문항은 없다", example = "1450000")
            Long sectionStartedOffsetMs) {

        private static QuestionGradingResponse from(SubmitQuizAnswersResult.QuestionGrading grading) {
            return new QuestionGradingResponse(
                    grading.questionId(),
                    grading.correct(),
                    grading.selectedOptionId(),
                    grading.correctOptionId(),
                    grading.explanation(),
                    grading.sectionStartedOffsetMs());
        }
    }
}
