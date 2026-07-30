package com.a105.zani.coach.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.application.port.CoachingTipType;

import static org.assertj.core.api.Assertions.assertThat;

/** §7.6 유형 선택. 85 가 비율을 double 4개로 넘기므로 최다·동률 판단도 이쪽에 있다. */
class CoachingTipTypeSelectorTest {

    private CoachingTipRatios ratios(
            int studentsCounted,
            double significant,
            double confused,
            double missed,
            double nonResponse,
            double unmeasurable) {
        return new CoachingTipRatios(studentsCounted, significant, confused, missed, nonResponse, unmeasurable);
    }

    @Test
    @DisplayName("분모가 0이면 유형을 고르지 않는다 — 판단할 학생이 없는 것과 아무도 어려워하지 않는 것은 다르다")
    void selectsNothingWhenNobodyIsCounted() {
        assertThat(CoachingTipTypeSelector.select(ratios(0, 0, 0, 0, 0, 0))).isEmpty();
    }

    @Test
    @DisplayName("유의 상태를 겪은 학생이 없으면 유형을 고르지 않는다")
    void selectsNothingWhenNobodyIsAtRisk() {
        assertThat(CoachingTipTypeSelector.select(ratios(10, 0, 0, 0, 0, 0))).isEmpty();
    }

    @Test
    @DisplayName("가장 높은 비율의 유형을 고른다")
    void selectsDominantRatio() {
        assertThat(CoachingTipTypeSelector.select(ratios(10, 0.6, 0.2, 0.5, 0.1, 0)))
                .contains(CoachingTipType.MISSED);
    }

    @Test
    @DisplayName("동률이면 CONFUSED → MISSED → NON_RESPONSE → UNMEASURABLE 순으로 고른다")
    void breaksTiesInFixedOrder() {
        assertThat(CoachingTipTypeSelector.select(ratios(10, 0.3, 0, 0, 0.3, 0.3)))
                .contains(CoachingTipType.NON_RESPONSE);
        assertThat(CoachingTipTypeSelector.select(ratios(10, 0.3, 0, 0.3, 0, 0.3)))
                .contains(CoachingTipType.MISSED);
        // 헷갈림과 겹칠 때는 놓침을 0으로 둔다. 둘 다 20%를 넘으면 동률 이전에 복합 조건이 먼저 성립한다.
        assertThat(CoachingTipTypeSelector.select(ratios(10, 0.3, 0.3, 0, 0, 0.3)))
                .contains(CoachingTipType.CONFUSED);
    }

    @Test
    @DisplayName("헷갈림·놓침이 동률이어도 둘 다 20%를 넘으면 동률 판정보다 복합 조건이 먼저다")
    void prefersBothHighOverTieBreakWhenBothExceedThreshold() {
        assertThat(CoachingTipTypeSelector.select(ratios(10, 0.5, 0.3, 0.3, 0, 0)))
                .contains(CoachingTipType.CONFUSED_AND_MISSED);
    }

    @Test
    @DisplayName("헷갈림·놓침이 각각 20%를 넘고 둘의 합이 나머지보다 크면 복합 팁이다")
    void selectsBothHigh() {
        assertThat(CoachingTipTypeSelector.select(ratios(10, 0.6, 0.3, 0.3, 0.1, 0)))
                .contains(CoachingTipType.CONFUSED_AND_MISSED);
    }

    @Test
    @DisplayName("정확히 20%면 복합이 아니다 — 규칙은 '초과'다")
    void requiresStrictlyMoreThanTwentyPercent() {
        // 둘 다 정확히 20% 면 복합에서 빠지고 동률 우선순위로 헷갈림이 남는다.
        assertThat(CoachingTipTypeSelector.select(ratios(10, 0.4, 0.2, 0.2, 0, 0)))
                .contains(CoachingTipType.CONFUSED);
    }

    @Test
    @DisplayName("둘의 합이 나머지보다 작으면 복합이 아니다 — 무응답 60%를 복합 팁이 가리면 안 된다")
    void doesNotSelectBothHighWhenAnotherStateDominates() {
        assertThat(CoachingTipTypeSelector.select(ratios(100, 0.9, 0.21, 0.21, 0.6, 0)))
                .contains(CoachingTipType.NON_RESPONSE);
    }

    @Test
    @DisplayName("자리비움이 가장 높으면 자리비움 팁이다")
    void selectsUnmeasurable() {
        assertThat(CoachingTipTypeSelector.select(ratios(10, 0.5, 0.1, 0, 0, 0.4)))
                .contains(CoachingTipType.UNMEASURABLE);
    }

    @Test
    @DisplayName("동률 우선순위는 79 의 TIE_BREAK_ORDER 와 같아야 한다")
    void tieBreakOrderMatchesAggregationSide() {
        // 79 는 AttentionState 로, 여기서는 CoachingTipType 으로 같은 순서를 갖는다.
        // 순서가 갈라지면 같은 비율에서 서로 다른 유형이 나와 원인을 찾기 어렵다.
        assertThat(com.a105.zani.attention.domain.model.CoachingSignalSummary.TIE_BREAK_ORDER)
                .containsExactly(
                        com.a105.zani.attention.domain.model.AttentionState.CONFUSED,
                        com.a105.zani.attention.domain.model.AttentionState.MISSED,
                        com.a105.zani.attention.domain.model.AttentionState.NON_RESPONSE,
                        com.a105.zani.attention.domain.model.AttentionState.UNMEASURABLE);
    }
}
