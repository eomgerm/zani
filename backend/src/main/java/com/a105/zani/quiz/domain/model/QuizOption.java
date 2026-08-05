package com.a105.zani.quiz.domain.model;

import com.a105.zani.quiz.domain.exception.InvalidQuizException;
import com.a105.zani.quiz.domain.exception.QuizErrorCode;

/** 문항 하나의 보기. 순서는 문항이 매긴다. */
public record QuizOption(String optionText, boolean correct, int optionOrder) {

    static final int UNSET_ORDER = 0;

    public QuizOption {
        optionText = optionText == null ? null : optionText.strip();
        if (optionText == null || optionText.isEmpty() || optionOrder < UNSET_ORDER) {
            throw new InvalidQuizException(QuizErrorCode.INVALID_QUIZ_QUESTION);
        }
    }

    public static QuizOption of(String optionText, boolean correct) {
        return new QuizOption(optionText, correct, UNSET_ORDER);
    }

    QuizOption withOrder(int optionOrder) {
        return new QuizOption(optionText, correct, optionOrder);
    }
}
