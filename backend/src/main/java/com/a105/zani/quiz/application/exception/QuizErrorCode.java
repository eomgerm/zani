package com.a105.zani.quiz.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

public enum QuizErrorCode implements ErrorCode {
    // 자기 퀴즈만 존재하는 자원이라, 참여하지 않은 세션의 퀴즈 요청은 전부 여기로 떨어진다(타인 퀴즈 403).
    NOT_SESSION_STUDENT(ErrorType.FORBIDDEN, "QUIZ_001", "Only the session's own student can access this quiz"),
    // 미생성(파이프라인 미완)과 진행 중 수업을 구분하지 않는다 — 학생에게는 둘 다 "아직 준비되지 않음"이다.
    QUIZ_NOT_READY(ErrorType.NOT_FOUND, "QUIZ_002", "The quiz for this session is not generated yet"),
    QUIZ_ALREADY_SUBMITTED(ErrorType.CONFLICT, "QUIZ_003", "The quiz answers were already submitted"),
    INVALID_QUIZ_ANSWERS(
            ErrorType.BAD_REQUEST,
            "QUIZ_004",
            "Answers must cover every question of the quiz exactly once, each selecting an option of that question");

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
