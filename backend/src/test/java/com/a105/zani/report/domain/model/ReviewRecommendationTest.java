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
        assertThatThrownBy(
                        () -> ReviewRecommendation.of(RecommendationType.LOW_ENGAGEMENT, "제목", "설명", 60_000L, 60_000L))
                .isInstanceOf(InvalidStudentReportException.class);
    }

    @Test
    void rejectsReversedSection() {
        assertThatThrownBy(
                        () -> ReviewRecommendation.of(RecommendationType.LOW_ENGAGEMENT, "제목", "설명", 180_000L, 60_000L))
                .isInstanceOf(InvalidStudentReportException.class);
    }

    @Test
    void rejectsNegativeStart() {
        assertThatThrownBy(() -> ReviewRecommendation.of(RecommendationType.LOW_ENGAGEMENT, "제목", "설명", -1L, 60_000L))
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
    void acceptsTheFiveDefinedTypes() {
        // 관측 하나에 유형 하나가 대응한다 — 프롬프트 응답 셋, 참여도 판정, 질문.
        assertThat(RecommendationType.values())
                .containsExactly(
                        RecommendationType.CONFUSED,
                        RecommendationType.MISSED,
                        RecommendationType.NO_RESPONSE,
                        RecommendationType.LOW_ENGAGEMENT,
                        RecommendationType.QUESTION);
        assertThat(RecommendationType.from("NO_RESPONSE")).isEqualTo(RecommendationType.NO_RESPONSE);
        assertThat(RecommendationType.from("LOW_ENGAGEMENT")).isEqualTo(RecommendationType.LOW_ENGAGEMENT);
    }

    @Test
    void rejectsUndefinedType() {
        assertThat(RecommendationType.from("QUESTION")).isEqualTo(RecommendationType.QUESTION);
        assertThatThrownBy(() -> RecommendationType.from("BORED")).isInstanceOf(InvalidStudentReportException.class);
        // 좁힌 유형이다. V12 가 남아 있던 행을 LOW_ENGAGEMENT 로 옮겼다.
        assertThatThrownBy(() -> RecommendationType.from("REPEAT")).isInstanceOf(InvalidStudentReportException.class);
    }
}
