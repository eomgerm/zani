package com.a105.zani.attention.domain.model;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 확정 문서 §7·§7.6 의 비율 계산과 동률 우선순위를 고정한다. */
class CoachingSignalSummaryTest {

    @Test
    void reportsNoRatioWhenThereIsNobodyToCount() {
        // 0.0 을 돌려주면 "아무도 어려워하지 않는다"로 읽힌다. 판단할 학생이 없는 것과 전혀 다른 상황이다(§7).
        assertTrue(CoachingSignalSummary.empty().ratio().isEmpty());
        assertTrue(
                CoachingSignalSummary.empty().ratioOf(AttentionState.CONFUSED).isEmpty());
    }

    @Test
    void separatesTwentyNinePercentFromThirty() {
        CoachingSignalSummary below = new CoachingSignalSummary(100, 29, Map.of(AttentionState.CONFUSED, 29));
        CoachingSignalSummary atThreshold = new CoachingSignalSummary(100, 30, Map.of(AttentionState.CONFUSED, 30));

        // 트리거 하한은 30%다(§7). 경계에서 갈리지 않으면 알림이 한 명 차이로 잘못 뜬다.
        assertTrue(below.ratio().getAsDouble() < 0.30d);
        assertTrue(atThreshold.ratio().getAsDouble() >= 0.30d);
    }

    @Test
    void countsAStudentOnceEvenWhenTwoStatesApply() {
        // 분자는 합집합이다. 상태별로 세어 더하면 두 상태를 겪은 학생이 두 번 세어져 비율이 부풀어 오른다.
        CoachingSignalSummary summary =
                new CoachingSignalSummary(10, 3, Map.of(AttentionState.CONFUSED, 3, AttentionState.UNMEASURABLE, 2));

        assertEquals(0.3d, summary.ratio().getAsDouble());
        assertEquals(0.3d, summary.ratioOf(AttentionState.CONFUSED).getAsDouble());
        assertEquals(0.2d, summary.ratioOf(AttentionState.UNMEASURABLE).getAsDouble());
    }

    @Test
    void picksTheStateMostStudentsExperienced() {
        CoachingSignalSummary summary = new CoachingSignalSummary(
                10, 6, Map.of(AttentionState.CONFUSED, 2, AttentionState.NON_RESPONSE, 5, AttentionState.MISSED, 1));

        assertEquals(AttentionState.NON_RESPONSE, summary.dominantState().orElseThrow());
    }

    @Test
    void breaksATieByTheConfirmedOrder() {
        // 같은 개수마다 팁이 달라지면 강사가 보기에 이유 없이 조언이 바뀐다(§7.6-4).
        assertEquals(
                AttentionState.CONFUSED,
                new CoachingSignalSummary(
                                10,
                                4,
                                Map.of(
                                        AttentionState.UNMEASURABLE, 2,
                                        AttentionState.NON_RESPONSE, 2,
                                        AttentionState.MISSED, 2,
                                        AttentionState.CONFUSED, 2))
                        .dominantState()
                        .orElseThrow());
        assertEquals(
                AttentionState.MISSED,
                new CoachingSignalSummary(
                                10,
                                4,
                                Map.of(
                                        AttentionState.UNMEASURABLE, 2,
                                        AttentionState.NON_RESPONSE, 2,
                                        AttentionState.MISSED, 2))
                        .dominantState()
                        .orElseThrow());
        assertEquals(
                AttentionState.NON_RESPONSE,
                new CoachingSignalSummary(10, 4, Map.of(AttentionState.UNMEASURABLE, 2, AttentionState.NON_RESPONSE, 2))
                        .dominantState()
                        .orElseThrow());
    }

    @Test
    void reportsNoDominantStateWhenNobodyStruggled() {
        assertTrue(new CoachingSignalSummary(10, 0, Map.of()).dominantState().isEmpty());
    }

    @Test
    void refusesStatesThatCannotEnterTheNumerator() {
        // GOOD·CAMERA_OFF 를 분자에 세면 카메라를 끈 학생이 문제 있는 학생으로 계산된다(§7.2).
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoachingSignalSummary(10, 1, Map.of(AttentionState.CAMERA_OFF, 1)));
        assertThrows(
                IllegalArgumentException.class, () -> new CoachingSignalSummary(10, 1, Map.of(AttentionState.GOOD, 1)));
    }

    @Test
    void refusesANumeratorLargerThanTheDenominator() {
        // 분모에 없는 학생을 셌다는 뜻이라 비율이 100%를 넘는다.
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoachingSignalSummary(2, 3, Map.of(AttentionState.CONFUSED, 3)));
    }

    @Test
    void keepsStudentIdentifiersOutOfTheSummary() {
        // 이 값은 LLM 입력(204)과 리포트(205)로 흘러간다. 개별 학생이 무엇을 했는지가 남으면 안 된다.
        CoachingSignalSummary summary = new CoachingSignalSummary(10, 1, Map.of(AttentionState.CONFUSED, 1));

        assertFalse(summary.toString().contains("participant"));
        assertEquals(3, CoachingSignalSummary.class.getRecordComponents().length);
    }
}
