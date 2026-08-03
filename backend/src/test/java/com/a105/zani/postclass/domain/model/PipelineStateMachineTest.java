package com.a105.zani.postclass.domain.model;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineStateMachineTest {

    private static final List<PipelineStatus> STAGES_IN_ORDER = List.of(
            PipelineStatus.QUEUED,
            PipelineStatus.TRANSCRIBING,
            PipelineStatus.ANALYZING,
            PipelineStatus.VALIDATING,
            PipelineStatus.PUBLISHED);

    @Test
    void advancesThroughEveryStageInOrder() {
        for (int stage = 0; stage < STAGES_IN_ORDER.size() - 1; stage++) {
            assertTrue(PipelineStateMachine.canAdvance(STAGES_IN_ORDER.get(stage), STAGES_IN_ORDER.get(stage + 1)));
        }
    }

    @Test
    void rejectsSkippingAStage() {
        // 전사 없이 분석으로 넘어가면 빈 입력으로 결과를 만들어 낸다.
        assertFalse(PipelineStateMachine.canAdvance(PipelineStatus.QUEUED, PipelineStatus.ANALYZING));
        assertFalse(PipelineStateMachine.canAdvance(PipelineStatus.QUEUED, PipelineStatus.PUBLISHED));
        assertFalse(PipelineStateMachine.canAdvance(PipelineStatus.TRANSCRIBING, PipelineStatus.VALIDATING));
        assertFalse(PipelineStateMachine.canAdvance(PipelineStatus.ANALYZING, PipelineStatus.PUBLISHED));
    }

    @Test
    void rejectsGoingBackToAnEarlierStage() {
        assertFalse(PipelineStateMachine.canAdvance(PipelineStatus.ANALYZING, PipelineStatus.TRANSCRIBING));
        assertFalse(PipelineStateMachine.canAdvance(PipelineStatus.VALIDATING, PipelineStatus.QUEUED));
    }

    @Test
    void failsFromEveryStageBeforePublishing() {
        assertTrue(PipelineStateMachine.canAdvance(PipelineStatus.QUEUED, PipelineStatus.FAILED));
        assertTrue(PipelineStateMachine.canAdvance(PipelineStatus.TRANSCRIBING, PipelineStatus.FAILED));
        assertTrue(PipelineStateMachine.canAdvance(PipelineStatus.ANALYZING, PipelineStatus.FAILED));
        assertTrue(PipelineStateMachine.canAdvance(PipelineStatus.VALIDATING, PipelineStatus.FAILED));
    }

    @Test
    void treatsPublishedAndFailedAsFinal() {
        for (PipelineStatus target : PipelineStatus.values()) {
            assertFalse(PipelineStateMachine.canAdvance(PipelineStatus.PUBLISHED, target));
            assertFalse(PipelineStateMachine.canAdvance(PipelineStatus.FAILED, target));
        }
    }

    @Test
    void knowsEveryStageSoANewOneCannotSlipInUnhandled() {
        // 단계를 더하면서 전이 규칙을 빠뜨리면 컴파일은 통과하고 그 단계를 처리할 때 NPE 가 난다.
        // 결과 조립 단계(S15P11A105-106)처럼 뒤에 붙을 수 있는 단계를 여기서 먼저 잡는다.
        for (PipelineStatus stage : PipelineStatus.values()) {
            assertDoesNotThrow(
                    () -> PipelineStateMachine.canAdvance(stage, PipelineStatus.FAILED),
                    () -> stage + " 의 전이 규칙이 정의되지 않았습니다");
        }
    }

    @Test
    void treatsTheSameStageAsNoAdvance() {
        // 중복 보고는 전이가 아니다. 멱등 처리는 호출자가 하고, 여기서는 옮길 수 없다고만 답한다.
        for (PipelineStatus stage : PipelineStatus.values()) {
            assertFalse(PipelineStateMachine.canAdvance(stage, stage));
        }
    }
}
