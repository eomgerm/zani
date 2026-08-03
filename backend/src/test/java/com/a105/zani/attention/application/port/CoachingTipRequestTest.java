package com.a105.zani.attention.application.port;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 트리거 스냅샷의 불변식과 비율 유도.
 *
 * <p>비율이 아니라 인원수를 담고 비율은 유도한다(티켓 205). 그래서 분모가 0 이거나 인원수가 분모를 넘으면 비율 자체가 뜻을 잃는다 — 계산 시점이 아니라 만드는 시점에 막는다.
 */
class CoachingTipRequestTest {

    private static final Instant TRIGGERED_AT = Instant.parse("2026-07-31T02:00:00Z");

    private static CoachingTipRequest request(int studentsCounted, int significantCount) {
        return new CoachingTipRequest(
                1L, "trigger-1", TRIGGERED_AT, studentsCounted, significantCount, 0, 0, 0, 0, null);
    }

    @Test
    @DisplayName("인원수에서 비율을 유도한다")
    void derivesRatiosFromCounts() {
        CoachingTipRequest snapshot = new CoachingTipRequest(1L, "trigger-1", TRIGGERED_AT, 5, 3, 2, 1, 4, 0, null);

        assertThat(snapshot.significantRatio()).isEqualTo(0.6);
        assertThat(snapshot.confusedRatio()).isEqualTo(0.4);
        assertThat(snapshot.missedRatio()).isEqualTo(0.2);
        assertThat(snapshot.nonResponseRatio()).isEqualTo(0.8);
        assertThat(snapshot.unmeasurableRatio()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("직전 팁은 없어도 된다 — 수업 첫 트리거")
    void allowsMissingPreviousTip() {
        assertThatCode(() -> request(5, 1)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("0명과 전원은 허용한다 — 분모 경계")
    void allowsZeroAndFullCounts() {
        assertThat(request(5, 0).significantRatio()).isEqualTo(0.0);
        assertThat(request(5, 5).significantRatio()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("세션·트리거 식별자와 트리거 시각은 필수다")
    void requiresSessionTriggerAndTime() {
        assertThatThrownBy(() -> new CoachingTipRequest(0L, "trigger-1", TRIGGERED_AT, 5, 1, 0, 0, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoachingTipRequest(1L, null, TRIGGERED_AT, 5, 1, 0, 0, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoachingTipRequest(1L, "   ", TRIGGERED_AT, 5, 1, 0, 0, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoachingTipRequest(1L, "trigger-1", null, 5, 1, 0, 0, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("분모가 0 이면 거절한다 — 비율이 0 나누기가 된다")
    void rejectsZeroDenominator() {
        assertThatThrownBy(() -> request(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(-1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("인원수가 음수면 거절한다")
    void rejectsNegativeCount() {
        assertThatThrownBy(() -> request(5, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("인원수가 분모를 넘으면 거절한다 — 비율이 1 을 넘어 팁 판정이 뒤틀린다")
    void rejectsCountAboveDenominator() {
        assertThatThrownBy(() -> request(5, 6)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("상태별 인원수도 모두 같은 기준으로 막는다")
    void rejectsEveryStateCountOutOfRange() {
        assertThatThrownBy(() -> new CoachingTipRequest(1L, "t", TRIGGERED_AT, 5, 0, 6, 0, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoachingTipRequest(1L, "t", TRIGGERED_AT, 5, 0, 0, -1, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoachingTipRequest(1L, "t", TRIGGERED_AT, 5, 0, 0, 0, 6, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoachingTipRequest(1L, "t", TRIGGERED_AT, 5, 0, 0, 0, 0, -1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
