package com.a105.zani.attention.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * 최근 5분 <b>익명</b> 집계(확정 문서 §7).
 *
 * <p>학생 식별자를 담지 않는다. 이 값은 LLM 입력(티켓 204)과 수업 후 리포트(205)로 흘러가므로, 개별 학생이 무엇을 했는지가 남으면 안 된다. 서비스는 계산에 참가자 ID 를 쓰지만 결과에는
 * 개수만 싣는다.
 *
 * <p>분자는 네 유의 상태를 겪은 학생의 <b>합집합</b>이다. 두 상태를 겪은 학생을 두 번 세면 비율이 부풀어 30% 임계를 잘못 넘긴다. 상태별 개수는 팁 유형 선택(§7.6)에 쓰므로 따로 담는다.
 *
 * @param denominator 세는 학생 수(§7.1). 0이면 비율을 계산하지 않는다
 * @param numerator 최근 5분에 유의 상태를 한 번이라도 겪은 <b>고유</b> 학생 수
 * @param countsByState 상태별 학생 수. 유의 상태만 담는다
 */
public record CoachingSignalSummary(int denominator, int numerator, Map<AttentionState, Integer> countsByState) {

    /**
     * 상태별 비율이 같을 때 고를 순서(§7.6-4).
     *
     * <p>결정론적 순서가 없으면 비율이 같을 때마다 팁이 달라져, 강사가 보기에 이유 없이 조언이 바뀐다.
     */
    public static final List<AttentionState> TIE_BREAK_ORDER = List.of(
            AttentionState.CONFUSED, AttentionState.MISSED, AttentionState.NON_RESPONSE, AttentionState.UNMEASURABLE);

    public CoachingSignalSummary {
        if (denominator < 0 || numerator < 0) {
            throw new IllegalArgumentException("집계 인원은 음수가 될 수 없습니다.");
        }
        if (numerator > denominator) {
            throw new IllegalArgumentException("분자가 분모보다 클 수 없습니다. 분모에 없는 학생을 셌다는 뜻입니다.");
        }
        countsByState = Map.copyOf(countsByState);
        for (AttentionState state : countsByState.keySet()) {
            if (!state.isSignificant()) {
                throw new IllegalArgumentException("유의 상태가 아닌 " + state + " 는 분자에 들어갈 수 없습니다.");
            }
        }
    }

    public static CoachingSignalSummary empty() {
        return new CoachingSignalSummary(0, 0, Map.of());
    }

    /**
     * 유의 학생 비율. 분모가 0이면 비어 있다.
     *
     * <p>0.0 을 돌려주지 않는 이유는 "아무도 어려워하지 않는다"와 "판단할 학생이 없다"가 전혀 다른 상황이기 때문이다. 트리거는 후자에서 판단을 미뤄야 한다(§7).
     */
    public OptionalDouble ratio() {
        return denominator == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) numerator / denominator);
    }

    /** 이 상태를 겪은 학생의 비율. 분모가 0이면 비어 있다. 팁 문구의 "전체 학생의 N%"가 이 값이다(§7.6). */
    public OptionalDouble ratioOf(AttentionState state) {
        return denominator == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) countOf(state) / denominator);
    }

    public int countOf(AttentionState state) {
        return countsByState.getOrDefault(state, 0);
    }

    /**
     * 가장 많은 학생이 겪은 유의 상태(§7.6-3·4). 같은 개수면 {@link #TIE_BREAK_ORDER} 순으로 고른다.
     *
     * <p>아무 상태도 없으면 비어 있다. 복합 팁("헷갈림과 놓침이 함께 높은 경우", §7.6-2) 판단은 팁 유형을 고르는 쪽(티켓 85)이 한다 — 여기서는 재료와 결정론적 순서만 준다.
     */
    public Optional<AttentionState> dominantState() {
        return TIE_BREAK_ORDER.stream()
                .filter(state -> countOf(state) > 0)
                .reduce((left, right) -> countOf(right) > countOf(left) ? right : left);
    }
}
