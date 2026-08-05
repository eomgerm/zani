package com.a105.zani.quiz.domain.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

/**
 * 퀴즈 <b>생성</b> 시의 구조 위반. {@link com.a105.zani.quiz.domain.model.Quiz} 애그리거트가 스스로 던진다.
 *
 * <p>코드 접두사를 {@code QUIZ_GEN_} 으로 띄운다. 같은 도메인의 조회·제출·채점(S15P11A105-255)이
 * {@code quiz.application.exception.QuizErrorCode} 에서 {@code QUIZ_001}~{@code QUIZ_004} 를 이미 쓰고 있어, 같은 문자열을 쓰면 클라이언트가
 * "남의 퀴즈 접근"(FORBIDDEN)과 "생성된 퀴즈 구조 위반"(BAD_REQUEST)을 코드로 구분할 수 없다. 응답 본문의 {@code code} 가 클라이언트의 유일한 분기 값이다.
 *
 * <p>{@code postclass} 가 이미 쓰는 방식과 같다 — {@code POSTCLASS_NOTE_*}(도메인 불변식)와 {@code POSTCLASS_JOB_*}·
 * {@code POSTCLASS_ANALYSIS_*}(유스케이스 조건)로 책임마다 접두사를 나눈다.
 */
public enum QuizErrorCode implements ErrorCode {
    INVALID_QUIZ(ErrorType.BAD_REQUEST, "QUIZ_GEN_001", "The quiz does not have three to five numbered questions"),
    INVALID_QUIZ_QUESTION(
            ErrorType.BAD_REQUEST,
            "QUIZ_GEN_002",
            "The quiz question does not have four options with exactly one answer");

    private final ErrorType type;
    private final String code;
    private final String message;

    QuizErrorCode(ErrorType type, String code, String message) {
        this.type = type;
        this.code = code;
        this.message = message;
    }

    @Override
    public ErrorType type() {
        return type;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
