package com.a105.zani.quiz.presentation.request;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.quiz.application.submitquizanswers.SubmitQuizAnswersCommand.QuizAnswerSelection;

@Schema(description = "퀴즈 답안 일괄 제출. 모든 문항을 정확히 한 번씩 담아야 하며, 제출 후에는 바꿀 수 없다.")
public record SubmitQuizAnswersRequest(
        @Schema(description = "문항별 선택 답안") @NotEmpty @Valid List<QuizAnswerRequest> answers) {

    public List<QuizAnswerSelection> toSelections() {
        return answers.stream()
                .map(answer -> new QuizAnswerSelection(answer.questionId(), answer.selectedOptionId()))
                .toList();
    }

    @Schema(description = "문항 하나에 대한 선택")
    public record QuizAnswerRequest(
            @Schema(description = "퀴즈 문항 ID", example = "742891573920571392") @NotNull Long questionId,

            @Schema(description = "선택한 보기 ID", example = "742891573920571397") @NotNull Long selectedOptionId) {}
}
