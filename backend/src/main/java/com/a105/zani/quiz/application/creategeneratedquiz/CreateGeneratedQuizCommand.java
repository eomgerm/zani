package com.a105.zani.quiz.application.creategeneratedquiz;

import java.util.List;

/**
 * AI 가 만든 퀴즈를 저장해 달라는 요청. 구조 규칙(문항 3~5, 보기 4개, 정답 1개)은 이 도메인이 검증한다.
 *
 * <p>호출 도메인이 {@code quiz} 의 도메인 모델을 import 하지 않도록 전부 원시 타입으로 받는다.
 */
public record CreateGeneratedQuizCommand(
        Long studentReportId, String title, String description, List<Question> questions) {

    /**
     * @param sectionStartedOffsetMs 문항이 가리키는 개념 구간의 시작 시각. 호출 도메인이 구간 번호를 이미 시각으로 되돌려 넘긴다. 근거 구간을 특정하지 못했으면
     *     {@code null}
     */
    public record Question(
            String questionText, String explanation, Long sectionStartedOffsetMs, List<Option> options) {}

    public record Option(String optionText, boolean correct) {}
}
