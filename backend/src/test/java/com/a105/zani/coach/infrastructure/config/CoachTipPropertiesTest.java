package com.a105.zani.coach.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실측으로 정한 기본값을 고정한다. 값이 바뀌면 근거(주석)도 함께 갱신해야 한다. */
class CoachTipPropertiesTest {

    @Test
    @DisplayName("설정을 비우면 실측 기준 기본값을 쓴다")
    void appliesMeasuredDefaults() {
        CoachTipProperties properties = new CoachTipProperties(null, null, null);

        assertThat(properties.minConfidence()).isEqualTo(0.5);
        // 근거 구절까지 받으면 실측 소비가 50 토큰이다(없으면 25). 관측치의 두 배로 둔다.
        assertThat(properties.maxCompletionTokens()).isEqualTo(100);
        // 말이 빠른 강사(8~9자/초)의 300초 전사 2,400~2,700자가 온전히 들어가는 상한.
        assertThat(properties.transcriptTailChars()).isEqualTo(3000);
    }

    @Test
    @DisplayName("0 이하 값은 기본값으로 되돌린다")
    void fallsBackOnNonPositiveValues() {
        CoachTipProperties properties = new CoachTipProperties(0.7, 0, -1);

        assertThat(properties.minConfidence()).isEqualTo(0.7);
        assertThat(properties.maxCompletionTokens()).isEqualTo(100);
        assertThat(properties.transcriptTailChars()).isEqualTo(3000);
    }

    @Test
    @DisplayName("신뢰도 하한이 0~1 밖이면 기동을 실패시킨다")
    void rejectsConfidenceOutsideUnitRange() {
        assertThatThrownBy(() -> new CoachTipProperties(1.5, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoachTipProperties(-0.1, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
