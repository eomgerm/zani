package com.a105.zani.coach.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 85 가 넘긴 값을 그대로 믿지 않고 막는 지점을 고정한다. */
class CoachingTipRatiosTest {

    @Test
    @DisplayName("분모 학생 수가 음수면 거절한다 — 집계가 깨진 값을 팁 문구까지 흘려보내지 않는다")
    void rejectsNegativeStudentsCounted() {
        assertThatThrownBy(() -> new CoachingTipRatios(-1, 0.3, 0.3, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
    }

    @Test
    @DisplayName("분모가 0이면 비어 있다 — 판단할 학생이 없는 것과 아무도 어려워하지 않는 것은 다르다")
    void isEmptyWhenNobodyIsCounted() {
        assertThat(new CoachingTipRatios(0, 0.3, 0.3, 0, 0, 0).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("유의 상태를 겪은 학생이 없으면 비어 있다")
    void isEmptyWhenNoStateHasAnyone() {
        assertThat(new CoachingTipRatios(10, 0, 0, 0, 0, 0).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("상태 하나라도 값이 있으면 비어 있지 않다")
    void isNotEmptyWhenAnyStateHasSomeone() {
        assertThat(new CoachingTipRatios(10, 0.1, 0, 0, 0, 0.1).isEmpty()).isFalse();
    }
}
