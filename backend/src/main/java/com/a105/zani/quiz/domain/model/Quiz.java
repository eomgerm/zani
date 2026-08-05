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

    /**
     * 문항 하나에 잡는 예상 풀이 시간(초). 예상 시간은 문항 수의 순수 함수라 이 애그리거트가 자기 값으로 계산한다 — 게시글의 "읽는 시간" 처럼 표시용 추정치이고, 정확할 수 없어 UI 도 "약" 을
     * 붙여 쓴다.
     *
     * <p>40초의 근거: 교육 평가의 통용 출발점은 4지선다 문항당 1분이지만, 그 값은 난이도에 따라 갈린다. Bloom 최하위 단계(정의·기억 확인) 문항은 30~60초, 복잡한 문항은 60~90초다.
     * 이 퀴즈는 성적·평가에 반영하지 않는 개념 확인용 4지선다(FRD §19.4)라 아래쪽 구간에 든다. 실제 학생 행동 관측 평균이 문항당 39초이고 formative 퀴즈에서 더 짧았다는 보고와도
     * 맞는다.
     *
     * <p>운영에서 응시 시간이 쌓이면 이 값을 실측으로 바꾼다.
     */
    private static final int SECONDS_PER_QUESTION = 40;

    private final Long studentReportId;
    private final String title;
    private final String description;
    private final int estimatedDurationMinutes;
    private final List<QuizQuestion> questions;

    private Quiz(
            Long studentReportId,
            String title,
            String description,
            int estimatedDurationMinutes,
            List<QuizQuestion> questions) {
        this.studentReportId = studentReportId;
        this.title = title;
        this.description = description;
        this.estimatedDurationMinutes = estimatedDurationMinutes;
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
        return new Quiz(
                studentReportId, quizTitle, quizDescription, estimatedDurationMinutes(numbered.size()), numbered);
    }

    /** 분 단위로 반올림한다. 올림하면 5문항이 4분이 되어 실제(3분 20초)보다 과하게 말한다. 최소 1분은 둔다 — "0분" 은 표시할 값이 아니다. */
    private static int estimatedDurationMinutes(int questionCount) {
        return Math.max(1, Math.round(questionCount * SECONDS_PER_QUESTION / 60.0f));
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

    public int estimatedDurationMinutes() {
        return estimatedDurationMinutes;
    }

    public List<QuizQuestion> questions() {
        return questions;
    }
}
