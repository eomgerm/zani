package com.a105.zani.attention.domain.model.timeline;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TimelinePolicyTest {

    @Test
    @DisplayName("기본값은 확정 문서와 FRD 가 정한 값을 그대로 쓴다")
    void defaults_match_the_decided_values() {
        TimelinePolicy policy = TimelinePolicy.defaults();

        // 세션 자동 종료 시각(Session.ACTIVE_DURATION)과 같은 값이다. 오프셋이 망가진 데이터에서 격자가
        // 무한히 늘어나는 것을 막는 안전장치다.
        assertThat(policy.maxDuration()).isEqualTo(Duration.ofHours(3));
        assertThat(policy.samplingInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.groupWindow()).isEqualTo(Duration.ofMinutes(5));
        // 겹치지 않는 칸의 크기다. 이동창이 아니다.
        assertThat(policy.focusBucket()).isEqualTo(Duration.ofSeconds(30));
        // 확정 문서 §6.2 의 상태 키 TTL 과 같은 값이다. 사후 재생도 같은 순간에 같은 판단을 해야 한다.
        assertThat(policy.connectionGap()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.requiredConnection()).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.measurementOutage()).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.significantTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.unmeasurableRunLength()).isEqualTo(3);
        assertThat(policy.focusCoverageFloor()).isEqualTo(0.7d);
        assertThat(policy.minimumEligible()).isEqualTo(5);
        assertThat(policy.distractionStartRatio()).isEqualTo(0.30d);
        assertThat(policy.distractionStartHold()).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.distractionEndRatio()).isEqualTo(0.20d);
        assertThat(policy.distractionEndHold()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.distractionMergeGap()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("70% 게이트는 30초 칸에서 판정 3건을 요구한다 — 2건은 20초라 못 넘는다")
    void the_coverage_floor_requires_three_observations() {
        TimelinePolicy policy = TimelinePolicy.defaults();

        long required = (long) (policy.focusBucket().toMillis() * policy.focusCoverageFloor());

        assertThat(required).isEqualTo(21_000L);
        assertThat(2 * 10_000L).isLessThan(required);
        assertThat(3 * 10_000L).isGreaterThanOrEqualTo(required);
    }
}
