package com.a105.zani.attention.domain.model;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoachingTriggerPolicyTest {

    private static final Duration ENOUGH_AUDIO = Duration.ofMinutes(2);

    private final CoachingTriggerPolicy policy =
            new CoachingTriggerPolicy(0.30, Duration.ofMinutes(10), Duration.ofMinutes(1));

    private static CoachingSignalSummary summary(int denominator, int numerator) {
        return new CoachingSignalSummary(
                denominator, numerator, numerator == 0 ? Map.of() : Map.of(AttentionState.CONFUSED, numerator));
    }

    @Test
    void doesNotTriggerJustBelowTheThreshold() {
        assertEquals(CoachingTriggerDecision.BELOW_THRESHOLD, policy.decide(summary(100, 29), ENOUGH_AUDIO));
    }

    @Test
    void triggersExactlyAtTheThreshold() {
        // 30% 는 넘는 것이 아니라 도달하면 트리거다(§7).
        assertEquals(CoachingTriggerDecision.TRIGGERED, policy.decide(summary(100, 30), ENOUGH_AUDIO));
    }

    @Test
    void triggersAtTheThresholdWhenTheRatioIsNotExactlyRepresentable() {
        // 3/10 은 이진 소수로 정확히 표현되지 않는다. 임계와 비교하는 방식이 경계를 흘리면 여기서 드러난다.
        assertEquals(CoachingTriggerDecision.TRIGGERED, policy.decide(summary(10, 3), ENOUGH_AUDIO));
    }

    @Test
    void doesNotTriggerWhenThereIsNoOneToCount() {
        // 분모 0 을 0% 로 다루면 아무도 없는 수업에서 "모두 잘 따라온다"고 판단한다(§7.1).
        assertEquals(CoachingTriggerDecision.NO_DENOMINATOR, policy.decide(summary(0, 0), ENOUGH_AUDIO));
    }

    @Test
    void doesNotTriggerWhenTheInstructorAudioIsJustShortOfTheMinimum() {
        assertEquals(CoachingTriggerDecision.BUFFER_TOO_SHORT, policy.decide(summary(10, 5), Duration.ofSeconds(59)));
    }

    @Test
    void triggersWhenTheInstructorAudioExactlyReachesTheMinimum() {
        assertEquals(CoachingTriggerDecision.TRIGGERED, policy.decide(summary(10, 5), Duration.ofSeconds(60)));
    }

    @Test
    void reportsTheRatioBeforeTheAudioLength() {
        // 비율이 낮은데 "오디오가 짧다"고 보고하면 쿨타임을 시작하지 않는 사유와 헷갈린다.
        assertEquals(CoachingTriggerDecision.BELOW_THRESHOLD, policy.decide(summary(100, 10), Duration.ofSeconds(1)));
    }

    @Test
    void rejectsAThresholdThatWouldTriggerOnEveryPoll() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoachingTriggerPolicy(0, Duration.ofMinutes(10), Duration.ofMinutes(1)));
    }

    @Test
    void rejectsAThresholdThatCanNeverBeReached() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoachingTriggerPolicy(1.5, Duration.ofMinutes(10), Duration.ofMinutes(1)));
    }

    @Test
    void rejectsACooldownThatWouldNeverPause() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoachingTriggerPolicy(0.30, Duration.ZERO, Duration.ofMinutes(1)));
    }
}
