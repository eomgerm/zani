package com.a105.zani.coach.domain.model;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.CoachingSignalSummary;

import static org.assertj.core.api.Assertions.assertThat;

/** §7.6 유형 선택. 복합 판정과 매핑만 이 도메인이 하고, 최다·동률은 79 의 {@code dominantState()} 를 쓴다. */
class CoachingTipTypeSelectorTest {

    private CoachingSignalSummary summary(int denominator, int numerator, Map<AttentionState, Integer> counts) {
        return new CoachingSignalSummary(denominator, numerator, counts);
    }

    @Test
    @DisplayName("분모가 0이면 유형을 고르지 않는다 — 판단할 학생이 없는 것과 아무도 어려워하지 않는 것은 다르다")
    void selectsNothingWhenDenominatorIsZero() {
        assertThat(CoachingTipTypeSelector.select(CoachingSignalSummary.empty()))
                .isEmpty();
    }

    @Test
    @DisplayName("유의 상태를 겪은 학생이 없으면 유형을 고르지 않는다")
    void selectsNothingWhenNobodyIsAtRisk() {
        assertThat(CoachingTipTypeSelector.select(summary(10, 0, Map.of()))).isEmpty();
    }

    @Test
    @DisplayName("가장 높은 비율의 유형을 고른다")
    void selectsDominantState() {
        CoachingSignalSummary summary = summary(
                10, 6, Map.of(AttentionState.CONFUSED, 2, AttentionState.MISSED, 5, AttentionState.NON_RESPONSE, 1));

        assertThat(CoachingTipTypeSelector.select(summary)).contains(CoachingTipType.MISSED_HIGH);
    }

    @Test
    @DisplayName("동률이면 CONFUSED → MISSED → NON_RESPONSE → UNMEASURABLE 순으로 고른다")
    void breaksTiesInFixedOrder() {
        assertThat(CoachingTipTypeSelector.select(
                        summary(10, 3, Map.of(AttentionState.NON_RESPONSE, 3, AttentionState.UNMEASURABLE, 3))))
                .contains(CoachingTipType.NON_RESPONSE_HIGH);
        assertThat(CoachingTipTypeSelector.select(
                        summary(10, 3, Map.of(AttentionState.MISSED, 3, AttentionState.UNMEASURABLE, 3))))
                .contains(CoachingTipType.MISSED_HIGH);
    }

    @Test
    @DisplayName("헷갈림·놓침이 각각 20%를 넘고 둘의 합이 나머지보다 크면 복합 팁이다")
    void selectsBothHighWhenConfusedAndMissedDominate() {
        CoachingSignalSummary summary = summary(
                10, 6, Map.of(AttentionState.CONFUSED, 3, AttentionState.MISSED, 3, AttentionState.NON_RESPONSE, 1));

        assertThat(CoachingTipTypeSelector.select(summary)).contains(CoachingTipType.CONFUSED_AND_MISSED_HIGH);
    }

    @Test
    @DisplayName("정확히 20%면 복합이 아니다 — 규칙은 '초과'다")
    void requiresStrictlyMoreThanTwentyPercent() {
        // 분모 10 에 각 2명이면 정확히 20% 다. 복합에서 빠지고 동률 우선순위로 헷갈림이 남는다.
        CoachingSignalSummary summary = summary(10, 4, Map.of(AttentionState.CONFUSED, 2, AttentionState.MISSED, 2));

        assertThat(CoachingTipTypeSelector.select(summary)).contains(CoachingTipType.CONFUSED_HIGH);
    }

    @Test
    @DisplayName("둘의 합이 나머지보다 작으면 복합이 아니다 — 무응답 60%를 복합 팁이 가리면 안 된다")
    void doesNotSelectBothHighWhenAnotherStateDominates() {
        CoachingSignalSummary summary = summary(
                100,
                90,
                Map.of(AttentionState.CONFUSED, 21, AttentionState.MISSED, 21, AttentionState.NON_RESPONSE, 60));

        assertThat(CoachingTipTypeSelector.select(summary)).contains(CoachingTipType.NON_RESPONSE_HIGH);
    }

    @Test
    @DisplayName("자리비움이 가장 높으면 자리비움 팁이다")
    void selectsUnmeasurable() {
        CoachingSignalSummary summary =
                summary(10, 5, Map.of(AttentionState.UNMEASURABLE, 4, AttentionState.CONFUSED, 1));

        assertThat(CoachingTipTypeSelector.select(summary)).contains(CoachingTipType.UNMEASURABLE_HIGH);
    }
}
