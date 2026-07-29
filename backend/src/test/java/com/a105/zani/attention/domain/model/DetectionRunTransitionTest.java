package com.a105.zani.attention.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 확정 문서 §7.3 의 {@code UNMEASURABLE} 연속 규칙을 고정한다. 서버·저장소가 공유하는 유일한 규칙 자리다. */
class DetectionRunTransitionTest {

    /** 전이를 순서대로 적용해 연속 횟수를 굴린다. 저장소가 하는 일을 그대로 흉내 낸다. */
    private static UnmeasurableRun after(DetectorOutcome... outcomes) {
        int consecutive = 0;
        for (DetectorOutcome outcome : outcomes) {
            consecutive = switch (DetectionRunTransition.of(outcome).unmeasurable()) {
                case INCREMENT -> consecutive + 1;
                case RESET -> 0;
            };
        }
        return new UnmeasurableRun(consecutive);
    }

    @Test
    void countsConsecutiveUnmeasurableWindows() {
        UnmeasurableRun run =
                after(DetectorOutcome.UNMEASURABLE, DetectorOutcome.UNMEASURABLE, DetectorOutcome.UNMEASURABLE);

        assertEquals(3, run.consecutive());
        assertTrue(run.isComplete());
    }

    @Test
    void doesNotLetASingleUnmeasurableWindowConfirmTheStudentState() {
        // 고개를 크게 돌리거나 자세를 고쳐 앉는 것만으로 한 창이 UNMEASURABLE 이 된다(§7.3).
        assertFalse(after(DetectorOutcome.UNMEASURABLE).isComplete());
        assertFalse(after(DetectorOutcome.UNMEASURABLE, DetectorOutcome.UNMEASURABLE)
                .isComplete());
    }

    @Test
    void breaksTheRunOnAnyRealObservation() {
        // 4단계 값이 나왔다면 관측이 실제로 됐다는 확실한 증거다.
        UnmeasurableRun run =
                after(DetectorOutcome.UNMEASURABLE, DetectorOutcome.UNMEASURABLE, DetectorOutcome.NOT_ENGAGED);

        assertEquals(0, run.consecutive());
    }

    @Test
    void breaksTheRunWhenTheCameraGoesOff() {
        UnmeasurableRun run =
                after(DetectorOutcome.UNMEASURABLE, DetectorOutcome.UNMEASURABLE, DetectorOutcome.CAMERA_OFF);

        assertEquals(UnmeasurableRun.none(), run);
    }

    @Test
    void breaksTheRunWhenTheDetectorCannotRun() {
        UnmeasurableRun run =
                after(DetectorOutcome.UNMEASURABLE, DetectorOutcome.UNMEASURABLE, DetectorOutcome.DETECTOR_UNAVAILABLE);

        assertEquals(UnmeasurableRun.none(), run);
    }

    @Test
    void restartsTheRunAfterAnInterruption() {
        UnmeasurableRun run = after(
                DetectorOutcome.UNMEASURABLE,
                DetectorOutcome.UNMEASURABLE,
                DetectorOutcome.ENGAGED,
                DetectorOutcome.UNMEASURABLE);

        // 이어서 세면 얼굴이 한 번 잡힌 학생이 두 창 만에 이탈자로 확정된다.
        assertEquals(1, run.consecutive());
        assertFalse(run.isComplete());
    }

    @Test
    void countsNoRunForEveryEngagementLevel() {
        // 저참여 연속은 서버가 세지 않는다. 이해 확인 프롬프트를 띄울지 판정하는 주체가 브라우저다(§5).
        for (DetectorOutcome outcome : DetectorOutcome.values()) {
            if (outcome.engagementLevel().isEmpty()) {
                continue;
            }
            assertEquals(0, after(outcome).consecutive(), outcome.name());
        }
    }
}
