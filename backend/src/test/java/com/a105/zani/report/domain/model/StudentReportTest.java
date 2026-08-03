package com.a105.zani.report.domain.model;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.a105.zani.report.domain.exception.InvalidStudentReportException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudentReportTest {

    private static final long SESSION_ID = 1L;
    private static final long PARTICIPANT_ID = 2L;

    @Test
    void assignsPriorityInListOrder() {
        StudentReport report = StudentReport.create(
                SESSION_ID,
                PARTICIPANT_ID,
                "요약",
                List.of(recommendation("첫째", 0L), recommendation("둘째", 60_000L), recommendation("셋째", 120_000L)));

        assertThat(report.recommendations())
                .extracting(ReviewRecommendation::priority)
                .containsExactly(1, 2, 3);
        assertThat(report.recommendations())
                .extracting(ReviewRecommendation::title)
                .containsExactly("첫째", "둘째", "셋째");
    }

    @Test
    void allowsNoRecommendations() {
        StudentReport report = StudentReport.create(SESSION_ID, PARTICIPANT_ID, "요약", List.of());

        assertThat(report.recommendations()).isEmpty();
    }

    @Test
    void rejectsMoreThanFiveRecommendations() {
        List<ReviewRecommendation> six = List.of(
                recommendation("1", 0L),
                recommendation("2", 60_000L),
                recommendation("3", 120_000L),
                recommendation("4", 180_000L),
                recommendation("5", 240_000L),
                recommendation("6", 300_000L));

        assertThatThrownBy(() -> StudentReport.create(SESSION_ID, PARTICIPANT_ID, "요약", six))
                .isInstanceOf(InvalidStudentReportException.class);
    }

    @Test
    void rejectsBlankSummary() {
        assertThatThrownBy(() -> StudentReport.create(SESSION_ID, PARTICIPANT_ID, "   ", List.of()))
                .isInstanceOf(InvalidStudentReportException.class);
    }

    @Test
    void rejectsSummaryLongerThanTwoThousandCharacters() {
        String tooLong = "가".repeat(2_001);

        assertThatThrownBy(() -> StudentReport.create(SESSION_ID, PARTICIPANT_ID, tooLong, List.of()))
                .isInstanceOf(InvalidStudentReportException.class);
    }

    private ReviewRecommendation recommendation(String title, long startedOffsetMs) {
        return ReviewRecommendation.of(
                RecommendationType.CONFUSED, title, "설명", startedOffsetMs, startedOffsetMs + 30_000L);
    }
}
