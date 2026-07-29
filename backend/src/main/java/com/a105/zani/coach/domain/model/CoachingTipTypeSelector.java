package com.a105.zani.coach.domain.model;

import java.util.Optional;

import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.CoachingSignalSummary;

/**
 * 익명 집계로 팁 유형을 고른다(기준 문서 §7.6). (S15P11A105-204)
 *
 * <p>§7.6 의 네 단계 중 셋은 79 가 이미 구현했다 — 상태별 비율은 {@code ratioOf}, 최다 선택과 동률 우선순위는 {@code dominantState()} 다. 여기서는 79 가 "팁
 * 유형을 고르는 쪽이 한다"며 남겨둔 복합 판정(§7.6-2)과 팁 유형 매핑만 한다. 같은 규칙을 두 곳에 두면 §7.6 을 고칠 때 한쪽만 바뀐다.
 *
 * <p>외부 의존이 없는 계산이라 도메인 순수 Java 로 둔다.
 */
public final class CoachingTipTypeSelector {

    /** 복합 팁의 하한(§7.6-2). 헷갈림·놓침이 각각 이 비율을 넘어야 한다. */
    private static final double BOTH_HIGH_THRESHOLD = 0.20;

    private CoachingTipTypeSelector() {}

    /**
     * 팁 유형을 고른다. 분모가 0이거나 유의 상태를 겪은 학생이 없으면 비어 있다.
     *
     * <p>분모 0에서 유형을 고르지 않는 이유: 79 가 {@code ratio()} 를 {@code OptionalDouble} 로 둔 것과 같다. "아무도 어려워하지 않는다"와 "판단할 학생이 없다"는
     * 다른 상황이고, 후자에서는 판단을 미뤄야 한다(§7).
     */
    public static Optional<CoachingTipType> select(CoachingSignalSummary summary) {
        if (summary.denominator() == 0) {
            return Optional.empty();
        }
        if (isBothHigh(summary)) {
            return Optional.of(CoachingTipType.CONFUSED_AND_MISSED_HIGH);
        }
        return summary.dominantState().map(CoachingTipType::of);
    }

    /**
     * 헷갈림과 놓침이 각각 20%를 넘고, 둘의 합이 나머지 두 유형보다 큰가(§7.6-2).
     *
     * <p>"둘의 합" 조건이 붙은 이유: 조건이 "각각 20% 초과"뿐이면 헷갈림 21%·놓침 21%·무응답 60% 일 때도 복합 팁이 떠서 정작 가장 큰 문제를 가린다. 복합 팁은 헷갈림과 놓침이 실제로
     * 지배적일 때만 뜬다.
     *
     * <p>합은 비율이 아니라 학생 수로 비교한다. 분모가 같아 결과는 동일하고 부동소수 비교를 피할 수 있다.
     */
    private static boolean isBothHigh(CoachingSignalSummary summary) {
        if (!exceedsThreshold(summary, AttentionState.CONFUSED) || !exceedsThreshold(summary, AttentionState.MISSED)) {
            return false;
        }
        int bothCount = summary.countOf(AttentionState.CONFUSED) + summary.countOf(AttentionState.MISSED);
        int restCount = summary.countOf(AttentionState.NON_RESPONSE) + summary.countOf(AttentionState.UNMEASURABLE);
        return bothCount > restCount;
    }

    private static boolean exceedsThreshold(CoachingSignalSummary summary, AttentionState state) {
        return summary.ratioOf(state).orElse(0) > BOTH_HIGH_THRESHOLD;
    }
}
