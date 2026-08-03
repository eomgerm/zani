package com.a105.zani.quiz.application.port;

import java.util.List;

/**
 * 퀴즈 문항 하나와 보기 전부. 보기는 표시 순서대로다.
 *
 * @param selectedOptionId 학생이 제출한 보기 ID. 미제출이면 {@code null}
 */
public record QuizQuestionSnapshot(
        Long questionId,
        String text,
        String explanation,
        int order,
        List<QuizOptionSnapshot> options,
        Long selectedOptionId) {

    /** 정답 보기 ID. MVP 는 단일 정답 객관식이라 첫 정답 보기를 쓴다. */
    public Long correctOptionId() {
        return options.stream()
                .filter(QuizOptionSnapshot::correct)
                .map(QuizOptionSnapshot::optionId)
                .findFirst()
                .orElse(null);
    }

    /** 제출된 답이 정답인지. 선택한 보기 자체의 정답 여부를 보므로 정답이 여럿이어도 판정이 흔들리지 않는다. */
    public boolean answeredCorrectly() {
        return isCorrectOption(selectedOptionId);
    }

    /** {@code optionId} 가 이 문항의 정답 보기인지. */
    public boolean isCorrectOption(Long optionId) {
        return optionId != null
                && options.stream().anyMatch(option -> option.optionId().equals(optionId) && option.correct());
    }

    /** {@code optionId} 가 이 문항의 보기인지 — 제출 검증(다른 문항의 보기 거절)에 쓴다. */
    public boolean hasOption(Long optionId) {
        return optionId != null
                && options.stream().anyMatch(option -> option.optionId().equals(optionId));
    }
}
