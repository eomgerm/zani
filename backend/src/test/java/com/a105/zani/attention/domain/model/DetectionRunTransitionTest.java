package com.a105.zani.attention.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 확정 문서 §4.1 의 카운터 전이표를 고정한다. 서버·저장소가 공유하는 유일한 규칙 자리다. */
class DetectionRunTransitionTest {

    /** 전이표를 순서대로 적용해 카운터를 굴린다. 저장소가 하는 일을 그대로 흉내 낸다. */
    private static DetectionRunCounters after(DetectionSignal... signals) {
        int low = 0;
        int unmeasurable = 0;
        for (DetectionSignal signal : signals) {
            DetectionRunTransition transition = DetectionRunTransition.of(signal);
            low = apply(transition.lowEngagement(), low);
            unmeasurable = apply(transition.unmeasurable(), unmeasurable);
        }
        return new DetectionRunCounters(low, unmeasurable);
    }

    private static int apply(RunStep step, int current) {
        return switch (step) {
            case INCREMENT -> current + 1;
            case RESET -> 0;
            case KEEP -> current;
        };
    }

    private static DetectionSignal low(DetectorOutcome outcome) {
        return new DetectionSignal(outcome, true);
    }

    private static DetectionSignal high(DetectorOutcome outcome) {
        return new DetectionSignal(outcome, false);
    }

    @Test
    void countsConsecutiveLowEngagementJudgements() {
        DetectionRunCounters counters = after(low(DetectorOutcome.NOT_ENGAGED), low(DetectorOutcome.BARELY_ENGAGED));

        assertEquals(2, counters.lowEngagement());
        assertFalse(counters.lowEngagementRunComplete());
    }

    @Test
    void completesTheLowEngagementRunOnTheThirdJudgement() {
        DetectionRunCounters counters = after(
                low(DetectorOutcome.NOT_ENGAGED),
                low(DetectorOutcome.NOT_ENGAGED),
                low(DetectorOutcome.BARELY_ENGAGED));

        assertTrue(counters.lowEngagementRunComplete());
    }

    @Test
    void keepsTheLowEngagementRunWhenAnUnmeasurableWindowInterrupts() {
        // 관측이 비었을 뿐 학생이 갑자기 집중하기 시작했다는 증거가 아니다.
        DetectionRunCounters counters = after(
                low(DetectorOutcome.NOT_ENGAGED),
                low(DetectorOutcome.NOT_ENGAGED),
                high(DetectorOutcome.UNMEASURABLE),
                low(DetectorOutcome.NOT_ENGAGED));

        assertTrue(counters.lowEngagementRunComplete());
    }

    @Test
    void breaksTheLowEngagementRunWhenTheStudentIsActuallyEngaged() {
        DetectionRunCounters counters = after(
                low(DetectorOutcome.NOT_ENGAGED),
                low(DetectorOutcome.NOT_ENGAGED),
                high(DetectorOutcome.ENGAGED),
                low(DetectorOutcome.NOT_ENGAGED));

        assertEquals(1, counters.lowEngagement());
        assertFalse(counters.lowEngagementRunComplete());
    }

    @Test
    void countsConsecutiveUnmeasurableWindows() {
        DetectionRunCounters counters = after(
                high(DetectorOutcome.UNMEASURABLE),
                high(DetectorOutcome.UNMEASURABLE),
                high(DetectorOutcome.UNMEASURABLE));

        assertTrue(counters.unmeasurableRunComplete());
    }

    @Test
    void breaksTheUnmeasurableRunOnAnyRealObservation() {
        // 4단계 값이 나왔다면 관측이 실제로 됐다는 확실한 증거다.
        DetectionRunCounters counters = after(
                high(DetectorOutcome.UNMEASURABLE),
                high(DetectorOutcome.UNMEASURABLE),
                low(DetectorOutcome.NOT_ENGAGED));

        assertEquals(0, counters.unmeasurable());
    }

    @Test
    void clearsBothCountersWhenTheCameraGoesOff() {
        DetectionRunCounters counters = after(
                low(DetectorOutcome.NOT_ENGAGED), high(DetectorOutcome.UNMEASURABLE), high(DetectorOutcome.CAMERA_OFF));

        assertEquals(DetectionRunCounters.none(), counters);
    }

    @Test
    void clearsBothCountersWhenTheDetectorCannotRun() {
        DetectionRunCounters counters = after(
                low(DetectorOutcome.NOT_ENGAGED),
                high(DetectorOutcome.UNMEASURABLE),
                high(DetectorOutcome.DETECTOR_UNAVAILABLE));

        assertEquals(DetectionRunCounters.none(), counters);
    }

    @Test
    void doesNotLetASingleUnmeasurableWindowConfirmTheStudentState() {
        // 고개를 크게 돌리거나 자세를 고쳐 앉는 것만으로 한 창이 UNMEASURABLE 이 된다(§7.3).
        assertFalse(after(high(DetectorOutcome.UNMEASURABLE)).unmeasurableRunComplete());
        assertFalse(after(high(DetectorOutcome.UNMEASURABLE), high(DetectorOutcome.UNMEASURABLE))
                .unmeasurableRunComplete());
    }

    @Test
    void countsALowEngagementSignalEvenWhenTheTopLevelIsEngaged() {
        // 1단계 0.20 / 2단계 0.15 / 3단계 0.60 / 4단계 0.05 → 가장 높은 건 3단계지만 저참여다(§3.3).
        DetectionRunCounters counters = after(
                new DetectionSignal(DetectorOutcome.ENGAGED, true),
                new DetectionSignal(DetectorOutcome.ENGAGED, true),
                new DetectionSignal(DetectorOutcome.ENGAGED, true));

        assertTrue(counters.lowEngagementRunComplete());
    }

    @Test
    void doesNotCountAnEngagedLevelThatIsNotFlaggedAsLowEngagement() {
        DetectionRunCounters counters = after(high(DetectorOutcome.NOT_ENGAGED));

        assertEquals(0, counters.lowEngagement());
    }
}
