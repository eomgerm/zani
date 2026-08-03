package com.a105.zani.coach.application.storehistory;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.application.port.CoachingTip;
import com.a105.zani.attention.application.port.CoachingTipType;
import com.a105.zani.attention.application.port.CoachingTipUnavailableReason;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A completed coaching result must remain self-consistent before it reaches the durable queue. */
class CoachingHistoryTest {

    private static final Instant TRIGGERED_AT = Instant.parse("2026-08-01T01:00:00Z");
    private static final Instant COMPLETED_AT = TRIGGERED_AT.plusSeconds(1);
    private static final CoachingResponseCounts COUNTS = new CoachingResponseCounts(10, 4, 3, 1, 0, 0);
    private static final CoachingTranscript TRANSCRIPT =
            CoachingTranscript.transcribed(TRIGGERED_AT.minusSeconds(30).toEpochMilli(), TRIGGERED_AT.toEpochMilli());
    private static final CoachingTip TIP = new CoachingTip(CoachingTipType.CONFUSED, "title", "message", "binary tree");

    @Test
    @DisplayName("성공과 실패 결과를 각각 하나의 완결된 이력으로 허용한다")
    void acceptsDeliveredAndUnavailableOutcomes() {
        assertThatCode(() -> history(TIP, null, CoachingTipType.CONFUSED)).doesNotThrowAnyException();
        assertThatCode(() -> history(null, CoachingTipUnavailableReason.LOW_CONFIDENCE, CoachingTipType.CONFUSED))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("세션·트리거 식별자와 두 처리 시각은 필수다")
    void requiresSessionTriggerAndTimes() {
        assertThatThrownBy(() -> new CoachingHistory(
                        0L,
                        "trigger-1",
                        TRIGGERED_AT,
                        COMPLETED_AT,
                        COUNTS,
                        CoachingTipType.CONFUSED,
                        TRANSCRIPT,
                        "topic",
                        TIP,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoachingHistory(
                        1L,
                        "   ",
                        TRIGGERED_AT,
                        COMPLETED_AT,
                        COUNTS,
                        CoachingTipType.CONFUSED,
                        TRANSCRIPT,
                        "topic",
                        TIP,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoachingHistory(
                        1L,
                        "trigger-1",
                        null,
                        COMPLETED_AT,
                        COUNTS,
                        CoachingTipType.CONFUSED,
                        TRANSCRIPT,
                        "topic",
                        TIP,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoachingHistory(
                        1L,
                        "trigger-1",
                        TRIGGERED_AT,
                        null,
                        COUNTS,
                        CoachingTipType.CONFUSED,
                        TRANSCRIPT,
                        "topic",
                        TIP,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("완료 시각이 트리거 시각보다 빠르면 거절한다")
    void rejectsCompletionBeforeTrigger() {
        assertThatThrownBy(() -> new CoachingHistory(
                        1L,
                        "trigger-1",
                        TRIGGERED_AT,
                        TRIGGERED_AT.minusMillis(1),
                        COUNTS,
                        CoachingTipType.CONFUSED,
                        TRANSCRIPT,
                        "topic",
                        TIP,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completion time");
    }

    @Test
    @DisplayName("익명 응답 수와 전사 상태는 반드시 함께 저장한다")
    void requiresCountsAndTranscript() {
        assertThatThrownBy(() -> new CoachingHistory(
                        1L,
                        "trigger-1",
                        TRIGGERED_AT,
                        COMPLETED_AT,
                        null,
                        CoachingTipType.CONFUSED,
                        TRANSCRIPT,
                        "topic",
                        TIP,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoachingHistory(
                        1L,
                        "trigger-1",
                        TRIGGERED_AT,
                        COMPLETED_AT,
                        COUNTS,
                        CoachingTipType.CONFUSED,
                        null,
                        "topic",
                        TIP,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("전달된 팁과 미제공 사유 중 정확히 하나만 허용한다")
    void requiresExactlyOneOutcome() {
        assertThatThrownBy(() -> history(null, null, CoachingTipType.CONFUSED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() ->
                        history(TIP, CoachingTipUnavailableReason.TIP_GENERATION_FAILED, CoachingTipType.CONFUSED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    @DisplayName("선택한 팁 유형과 최종 팁 유형이 다르면 거절한다")
    void requiresSelectedAndDeliveredTipTypesToMatch() {
        assertThatThrownBy(() -> history(TIP, null, CoachingTipType.MISSED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tip types");
    }

    @Test
    @DisplayName("내용 없는 주제는 저장하지 않도록 null로 정규화한다")
    void normalizesBlankTopic() {
        CoachingHistory history = new CoachingHistory(
                1L,
                "trigger-1",
                TRIGGERED_AT,
                COMPLETED_AT,
                COUNTS,
                CoachingTipType.CONFUSED,
                TRANSCRIPT,
                "   ",
                TIP,
                null);

        assertThat(history.topic()).isNull();
    }

    private CoachingHistory history(
            CoachingTip tip, CoachingTipUnavailableReason unavailableReason, CoachingTipType selectedTipType) {
        return new CoachingHistory(
                1L,
                "trigger-1",
                TRIGGERED_AT,
                COMPLETED_AT,
                COUNTS,
                selectedTipType,
                TRANSCRIPT,
                "binary tree",
                tip,
                unavailableReason);
    }
}
