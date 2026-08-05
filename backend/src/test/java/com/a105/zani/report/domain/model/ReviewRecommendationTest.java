package com.a105.zani.report.domain.model;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.application.port.StudentAnalysis;
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
    void staysInStepWithTheTypeListSentToTheModel() {
        // 두 목록이 갈라지면 조용하지 않게 망가진다 — ground() 가 통과시킨 값을 from() 이 거절하고,
        // 그 학생은 FAILED 로 남는다. 원인이 모델 응답이 아니라 코드라서 다음 실행에서도 같은 이유로
        // 실패하고, 멱등 바깥 겹의 재시도로 영영 복구되지 않는다.
        assertThat(StudentAnalysis.RECOMMENDATION_TYPES)
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(RecommendationType.values())
                        .map(Enum::name)
                        .toList());
    }

    @Test
    void rejectsUndefinedType() {
        assertThat(RecommendationType.from("QUESTION")).isEqualTo(RecommendationType.QUESTION);
        assertThatThrownBy(() -> RecommendationType.from("BORED")).isInstanceOf(InvalidStudentReportException.class);
        // 좁힌 유형이다. V12 가 남아 있던 행을 LOW_ENGAGEMENT 로 옮겼다.
        assertThatThrownBy(() -> RecommendationType.from("REPEAT")).isInstanceOf(InvalidStudentReportException.class);
    }
}
