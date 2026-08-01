package com.a105.zani.coach.application.port;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 설정을 application 타입으로 옮기는 지점의 불변식.
 *
 * <p>{@code CoachTipProperties} 가 기본값으로 되돌려 주는 값이라 정상 경로에서는 어긋나지 않는다. 다만 그 변환을 거치지 않고 이 타입을 직접 만드는 코드가 생기면 잘못된 값이 파이프라인
 * 깊숙이 들어가므로 경계에서 막는다.
 */
class CoachingTipSettingsTest {

    @Test
    @DisplayName("정상 값을 그대로 담는다")
    void keepsValidValues() {
        CoachingTipSettings settings = new CoachingTipSettings(0.5, 3000, Duration.ofSeconds(10));

        assertThat(settings.minConfidence()).isEqualTo(0.5);
        assertThat(settings.transcriptTailChars()).isEqualTo(3000);
        assertThat(settings.maxTriggerDelay()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("신뢰도 하한이 0~1 밖이면 거절한다")
    void rejectsConfidenceOutsideUnitRange() {
        assertThatThrownBy(() -> new CoachingTipSettings(1.5, 3000, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoachingTipSettings(-0.1, 3000, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("전사 상한이 0 이하면 거절한다 — 프롬프트에 아무것도 넣지 못한다")
    void rejectsNonPositiveTranscriptTail() {
        assertThatThrownBy(() -> new CoachingTipSettings(0.5, 0, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("지연 상한이 0 이하이거나 없으면 거절한다 — 모든 트리거가 상한 초과가 된다")
    void rejectsNonPositiveMaxTriggerDelay() {
        assertThatThrownBy(() -> new CoachingTipSettings(0.5, 3000, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoachingTipSettings(0.5, 3000, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
