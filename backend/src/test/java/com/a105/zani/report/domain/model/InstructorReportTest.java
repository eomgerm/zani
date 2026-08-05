package com.a105.zani.report.domain.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.report.domain.exception.InvalidInstructorReportException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstructorReportTest {

    private static final Long SESSION_ID = 4_242L;
    private static final String FEEDBACK = "전체 흐름은 개념 → 문제 → 해법 순으로 잘 짜여 있었습니다.";

    private static Map<EvaluationType, Integer> allScores() {
        Map<EvaluationType, Integer> scores = new EnumMap<>(EvaluationType.class);
        scores.put(EvaluationType.DELIVERY, 88);
        scores.put(EvaluationType.STRUCTURE_FLOW, 84);
        scores.put(EvaluationType.INTERACTION, 71);
        scores.put(EvaluationType.DIFFICULTY_CONTROL, 76);
        return scores;
    }

    private static ClassInsight insight(String title) {
        return ClassInsight.of(title, "근거 문장입니다.", "제안 문장입니다.", null, null);
    }

    @Test
    @DisplayName("점수 4종과 인사이트를 담아 리포트를 만든다")
    void creates_a_report() {
        InstructorReport report =
                InstructorReport.create(SESSION_ID, FEEDBACK, 12, allScores(), List.of(insight("가"), insight("나")));

        assertThat(report.sessionId()).isEqualTo(SESSION_ID);
        assertThat(report.overallFeedback()).isEqualTo(FEEDBACK);
        assertThat(report.questionCount()).isEqualTo(12);
        assertThat(report.scores()).containsEntry(EvaluationType.INTERACTION, 71);
    }

    @Test
    @DisplayName("인사이트 순서를 준 대로 유지한다 — 저장 어댑터가 이 순서로 TSID 를 발급한다")
    void keeps_the_given_insight_order() {
        InstructorReport report = InstructorReport.create(
                SESSION_ID, FEEDBACK, 0, allScores(), List.of(insight("가"), insight("나"), insight("다")));

        assertThat(report.insights()).extracting(ClassInsight::title).containsExactly("가", "나", "다");
    }

    @Test
    @DisplayName("인사이트 0개도 유효하다 — 근거가 2종 모이지 않는 수업이 있다")
    void an_empty_insight_list_is_valid() {
        assertThat(InstructorReport.create(SESSION_ID, FEEDBACK, 0, allScores(), List.of())
                        .insights())
                .isEmpty();
    }

    @Test
    @DisplayName("평가 분야 4종이 다 오지 않으면 거절한다 — 화면이 도넛 4개를 그린다")
    void rejects_a_partial_score_set() {
        Map<EvaluationType, Integer> partial = allScores();
        partial.remove(EvaluationType.DIFFICULTY_CONTROL);

        assertThatThrownBy(() -> InstructorReport.create(SESSION_ID, FEEDBACK, 0, partial, List.of()))
                .isInstanceOf(InvalidInstructorReportException.class);
        assertThatThrownBy(() -> InstructorReport.create(SESSION_ID, FEEDBACK, 0, null, List.of()))
                .isInstanceOf(InvalidInstructorReportException.class);
    }

    @Test
    @DisplayName("0~100 밖 점수를 거절한다")
    void rejects_a_score_out_of_range() {
        Map<EvaluationType, Integer> tooHigh = allScores();
        tooHigh.put(EvaluationType.DELIVERY, 101);
        Map<EvaluationType, Integer> negative = allScores();
        negative.put(EvaluationType.DELIVERY, -1);

        assertThatThrownBy(() -> InstructorReport.create(SESSION_ID, FEEDBACK, 0, tooHigh, List.of()))
                .isInstanceOf(InvalidInstructorReportException.class);
        assertThatThrownBy(() -> InstructorReport.create(SESSION_ID, FEEDBACK, 0, negative, List.of()))
                .isInstanceOf(InvalidInstructorReportException.class);
    }

    @Test
    @DisplayName("종합 피드백이 없거나 2000자를 넘으면 거절한다")
    void rejects_bad_feedback() {
        assertThatThrownBy(() -> InstructorReport.create(SESSION_ID, "  ", 0, allScores(), List.of()))
                .isInstanceOf(InvalidInstructorReportException.class);
        assertThatThrownBy(() -> InstructorReport.create(SESSION_ID, "가".repeat(2_001), 0, allScores(), List.of()))
                .isInstanceOf(InvalidInstructorReportException.class);
    }

    @Test
    @DisplayName("음수 질문 수를 거절한다")
    void rejects_a_negative_question_count() {
        assertThatThrownBy(() -> InstructorReport.create(SESSION_ID, FEEDBACK, -1, allScores(), List.of()))
                .isInstanceOf(InvalidInstructorReportException.class);
    }

    @Test
    @DisplayName("세션 ID 가 없으면 거절한다")
    void rejects_a_missing_session_id() {
        assertThatThrownBy(() -> InstructorReport.create(null, FEEDBACK, 0, allScores(), List.of()))
                .isInstanceOf(InvalidInstructorReportException.class);
    }

    @Test
    @DisplayName("인사이트 상한을 넘으면 거절한다 — 자르는 것은 호출부의 근거 검증이 한다")
    void rejects_more_insights_than_the_cap() {
        List<ClassInsight> tooMany = List.of(insight("1"), insight("2"), insight("3"), insight("4"), insight("5"));

        assertThatThrownBy(() -> InstructorReport.create(SESSION_ID, FEEDBACK, 0, allScores(), tooMany))
                .isInstanceOf(InvalidInstructorReportException.class);
    }

    @Test
    @DisplayName("정의되지 않은 평가 분야 문자열을 거절한다")
    void rejects_an_undefined_evaluation_type() {
        assertThatThrownBy(() -> EvaluationType.from("PERSONALITY"))
                .isInstanceOf(InvalidInstructorReportException.class);
        assertThat(EvaluationType.from("STRUCTURE_FLOW")).isEqualTo(EvaluationType.STRUCTURE_FLOW);
    }
}
