package com.a105.zani.attention.domain.model;

/**
 * 코칭 트리거 판정 결과(확정 문서 §7).
 *
 * <p>거절 사유를 남기는 이유는 세 상황이 전혀 다르기 때문이다 — 셀 학생이 없는 것, 학생들이 잘 따라오는 것, 강사가 아직 말을 적게 한 것. 코칭이 조용한 이유를 나중에 설명할 수 있어야 한다.
 */
public enum CoachingTriggerDecision {
    /** 유의 학생 비율이 임계를 넘고 전사할 오디오도 확보됐다. */
    TRIGGERED,

    /** 세는 학생이 없다(§7.1). 0%와 구분해야 하므로 별도 사유로 둔다. */
    NO_DENOMINATOR,

    /** 유의 학생 비율이 임계 미만이다. */
    BELOW_THRESHOLD,

    /** 강사 오디오가 최소 길이에 못 미친다. 유일하게 쿨타임을 시작하지 않는 사유다(§7). */
    BUFFER_TOO_SHORT;

    public boolean triggered() {
        return this == TRIGGERED;
    }
}
