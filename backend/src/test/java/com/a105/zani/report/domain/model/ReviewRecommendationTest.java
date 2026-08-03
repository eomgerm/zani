package com.a105.zani.report.domain.model;

import org.junit.jupiter.api.Test;

import com.a105.zani.report.domain.exception.InvalidStudentReportException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewRecommendationTest {

    @Test
    void keepsSectionTimesAndTrimsText() {
        ReviewRecommendation recommendation =
                ReviewRecommendation.of(RecommendationType.CONFUSED, "  이차방정식  ", "  다시 보기  ", 60_000L, 180_000L);

        assertThat(recommendation.title()).isEqualTo("이차방정식");
        assertThat(recommendation.description()).isEqualTo("다시 보기");
        assertThat(recommendation.startedOffsetMs()).isEqualTo(60_000L);
        assertThat(recommendation.endedOffsetMs()).isEqualTo(180_000L);
    }

    @Test
    void rejectsZeroLengthSection() {
        assertThatThrownBy(() -> ReviewRecommendation.of(RecommendationType.REPEAT, "제목", "설명", 60_000L, 60_000L))
                .isInstanceOf(InvalidStudentReportException.class);
    }

    @Test
    void rejectsReversedSection() {
        assertThatThrownBy(() -> ReviewRecommendation.of(RecommendationType.REPEAT, "제목", "설명", 180_000L, 60_000L))
                .isInstanceOf(InvalidStudentReportException.class);
    }

    @Test
    void rejectsNegativeStart() {
        assertThatThrownBy(() -> ReviewRecommendation.of(RecommendationType.REPEAT, "제목", "설명", -1L, 60_000L))
                .isInstanceOf(InvalidStudentReportException.class);
    }

    @Test
    void rejectsMissingType() {
        assertThatThrownBy(() -> ReviewRecommendation.of(null, "제목", "설명", 0L, 60_000L))
                .isInstanceOf(InvalidStudentReportException.class);
    }

    @Test
    void rejectsBlankTitleOrDescription() {
        assertThatThrownBy(() -> ReviewRecommendation.of(RecommendationType.MISSED, "   ", "설명", 0L, 60_000L))
                .isInstanceOf(InvalidStudentReportException.class);
        assertThatThrownBy(() -> ReviewRecommendation.of(RecommendationType.MISSED, "제목", "   ", 0L, 60_000L))
                .isInstanceOf(InvalidStudentReportException.class);
    }

    @Test
    void rejectsUndefinedType() {
        assertThat(RecommendationType.from("QUESTION")).isEqualTo(RecommendationType.QUESTION);
        assertThatThrownBy(() -> RecommendationType.from("BORED")).isInstanceOf(InvalidStudentReportException.class);
    }
}
