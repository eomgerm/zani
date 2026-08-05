package com.a105.zani.report.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.report.domain.exception.InvalidInstructorReportException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClassInsightTest {

    private static final String TITLE = "어려운 구간 보강";
    private static final String CONTENT = "확인 필요 신호와 공개 질문이 같은 구간에 몰렸습니다.";
    private static final String SUGGESTION = "예시 코드와 실습 시간을 늘려보세요.";

    @Test
    @DisplayName("구간이 있는 인사이트를 만든다")
    void creates_an_insight_with_a_section_range() {
        ClassInsight insight = ClassInsight.of(TITLE, CONTENT, SUGGESTION, 520_000L, 921_000L);

        assertThat(insight.title()).isEqualTo(TITLE);
        assertThat(insight.content()).isEqualTo(CONTENT);
        assertThat(insight.suggestion()).isEqualTo(SUGGESTION);
        assertThat(insight.startedOffsetMs()).isEqualTo(520_000L);
        assertThat(insight.endedOffsetMs()).isEqualTo(921_000L);
    }

    @Test
    @DisplayName("구간이 없으면 전체 수업 대상이다 — 시각 둘 다 비어 있다")
    void a_whole_class_insight_has_no_offsets() {
        ClassInsight insight = ClassInsight.of(TITLE, CONTENT, SUGGESTION, null, null);

        assertThat(insight.startedOffsetMs()).isNull();
        assertThat(insight.endedOffsetMs()).isNull();
    }

    @Test
    @DisplayName("시각이 한쪽만 있으면 거절한다 — 어디까지인지 모르는 구간은 복습 링크를 만들 수 없다")
    void rejects_a_half_open_range() {
        assertThatThrownBy(() -> ClassInsight.of(TITLE, CONTENT, SUGGESTION, 520_000L, null))
                .isInstanceOf(InvalidInstructorReportException.class);
        assertThatThrownBy(() -> ClassInsight.of(TITLE, CONTENT, SUGGESTION, null, 921_000L))
                .isInstanceOf(InvalidInstructorReportException.class);
    }

    @Test
    @DisplayName("종료가 시작보다 앞서면 거절한다")
    void rejects_a_reversed_range() {
        assertThatThrownBy(() -> ClassInsight.of(TITLE, CONTENT, SUGGESTION, 921_000L, 520_000L))
                .isInstanceOf(InvalidInstructorReportException.class);
    }

    @Test
    @DisplayName("길이 0 구간은 허용한다 — 한 점을 짚는 관찰이 있다")
    void allows_a_zero_length_range() {
        assertThat(ClassInsight.of(TITLE, CONTENT, SUGGESTION, 520_000L, 520_000L)
                        .endedOffsetMs())
                .isEqualTo(520_000L);
    }

    @Test
    @DisplayName("제목·근거·제안은 필수다")
    void rejects_missing_text() {
        assertThatThrownBy(() -> ClassInsight.of("  ", CONTENT, SUGGESTION, null, null))
                .isInstanceOf(InvalidInstructorReportException.class);
        assertThatThrownBy(() -> ClassInsight.of(TITLE, null, SUGGESTION, null, null))
                .isInstanceOf(InvalidInstructorReportException.class);
        assertThatThrownBy(() -> ClassInsight.of(TITLE, CONTENT, "", null, null))
                .isInstanceOf(InvalidInstructorReportException.class);
    }

    @Test
    @DisplayName("제목 200자를 넘으면 거절한다 — 컬럼이 VARCHAR(200) 이다")
    void rejects_a_title_longer_than_the_column() {
        assertThat(ClassInsight.of("가".repeat(200), CONTENT, SUGGESTION, null, null)
                        .title())
                .hasSize(200);
        assertThatThrownBy(() -> ClassInsight.of("가".repeat(201), CONTENT, SUGGESTION, null, null))
                .isInstanceOf(InvalidInstructorReportException.class);
    }

    @Test
    @DisplayName("앞뒤 공백은 다듬어 저장한다")
    void strips_surrounding_whitespace() {
        ClassInsight insight = ClassInsight.of("  " + TITLE + "  ", CONTENT, SUGGESTION, null, null);

        assertThat(insight.title()).isEqualTo(TITLE);
    }
}
