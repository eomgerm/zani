package com.a105.zani.quiz.presentation.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.quiz.application.getstudentquiz.GetStudentQuizResult;

/** 본인 퀴즈 화면. 정답·해설은 제출 후에만 문항의 {@code grading} 으로 내려간다 — 제출 전 응답에는 필드 자체가 없다. */
@Schema(description = "학생 본인 퀴즈. 제출 전에는 문항·보기만, 제출 후에는 문항별 채점(grading)이 함께 담긴다.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StudentQuizResponse(
        @Schema(description = "퀴즈 ID", example = "742891573920571390")
        Long quizId,

        @Schema(description = "퀴즈 제목", example = "재귀 함수 복습 퀴즈")
        String title,

        @Schema(description = "퀴즈 설명") String description,
        @Schema(description = "예상 풀이 시간(분)", example = "10") Short estimatedDurationMinutes,
        @Schema(description = "답안 제출 완료 여부") boolean submitted,
        @Schema(description = "문항 목록(출제 순서)") List<QuestionResponse> questions) {

    public static StudentQuizResponse from(GetStudentQuizResult result) {
        return new StudentQuizResponse(
                result.quizId(),
                result.title(),
                result.description(),
                result.estimatedDurationMinutes(),
                result.submitted(),
                result.questions().stream().map(QuestionResponse::from).toList());
    }

    @Schema(description = "퀴즈 문항. grading 은 제출 후에만 담긴다.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuestionResponse(
            @Schema(description = "문항 ID", example = "742891573920571392")
            Long questionId,

            @Schema(description = "퀴즈 내 문항 순서", example = "1")
            int order,

            @Schema(description = "문항 내용") String text,

            @Schema(description = "보기 목록(표시 순서). 정답 여부는 담기지 않는다.")
            List<OptionResponse> options,

            @Schema(description = "문항별 채점. 제출 전에는 없다.") QuestionGradingResponse grading) {

        private static QuestionResponse from(GetStudentQuizResult.QuestionResult question) {
            return new QuestionResponse(
                    question.questionId(),
                    question.order(),
                    question.text(),
                    question.options().stream().map(OptionResponse::from).toList(),
                    question.grading() == null ? null : QuestionGradingResponse.from(question.grading()));
        }
    }

    @Schema(description = "문항의 보기")
    public record OptionResponse(
            @Schema(description = "보기 ID", example = "742891573920571397")
            Long optionId,

            @Schema(description = "문항 내 보기 순서", example = "1")
            int order,

            @Schema(description = "보기 내용") String text) {

        private static OptionResponse from(GetStudentQuizResult.OptionResult option) {
            return new OptionResponse(option.optionId(), option.order(), option.text());
        }
    }

    @Schema(description = "제출 후에만 내려가는 문항별 채점")
    public record QuestionGradingResponse(
            @Schema(description = "정답 여부") boolean correct,
            @Schema(description = "학생이 선택한 보기 ID") Long selectedOptionId,
            @Schema(description = "정답 보기 ID") Long correctOptionId,
            @Schema(description = "해설") String explanation) {

        private static QuestionGradingResponse from(GetStudentQuizResult.QuestionGrading grading) {
            return new QuestionGradingResponse(
                    grading.correct(), grading.selectedOptionId(), grading.correctOptionId(), grading.explanation());
        }
    }
}
