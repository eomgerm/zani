package com.a105.zani.quiz.domain.model;

import java.util.List;
import java.util.stream.IntStream;

import com.a105.zani.quiz.domain.exception.InvalidQuizException;
import com.a105.zani.quiz.domain.exception.QuizErrorCode;

/**
 * 학생 리포트 하나에 딸린 AI 퀴즈(UK_QUIZZES_STUDENT_REPORT — 리포트당 한 개).
 *
 * <p>문항 수 3~5 는 이 애그리거트가 지킨다. 어긴 응답은 그 학생의 분석 전체를 실패로 만든다 — 문항이 두 개인 퀴즈를 저장해 두면 응시 화면이 그 상태를 다뤄야 한다.
 */
public final class Quiz {

    private static final int MIN_QUESTIONS = 3;
    private static final int MAX_QUESTIONS = 5;

    private final Long studentReportId;
    private final String title;
    private final String description;
    private final List<QuizQuestion> questions;

    private Quiz(Long studentReportId, String title, String description, List<QuizQuestion> questions) {
        this.studentReportId = studentReportId;
        this.title = title;
        this.description = description;
        this.questions = questions;
    }

    public static Quiz create(Long studentReportId, String title, String description, List<QuizQuestion> questions) {
        String quizTitle = title == null ? null : title.strip();
        String quizDescription = description == null || description.isBlank() ? null : description.strip();
        List<QuizQuestion> given = questions == null ? List.of() : questions;
        if (studentReportId == null
                || quizTitle == null
                || quizTitle.isEmpty()
                || given.size() < MIN_QUESTIONS
                || given.size() > MAX_QUESTIONS) {
            throw new InvalidQuizException(QuizErrorCode.INVALID_QUIZ);
        }
        List<QuizQuestion> numbered = IntStream.range(0, given.size())
                .mapToObj(index -> given.get(index).withOrder(index + 1))
                .toList();
        return new Quiz(studentReportId, quizTitle, quizDescription, numbered);
    }

    public Long studentReportId() {
        return studentReportId;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public List<QuizQuestion> questions() {
        return questions;
    }
}
