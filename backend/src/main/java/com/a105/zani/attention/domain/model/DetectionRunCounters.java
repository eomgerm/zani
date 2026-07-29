package com.a105.zani.attention.domain.model;

/**
 * 검출기 출력의 연속 횟수 두 개(확정 문서 §4.1). 판정 건수를 세는 값은 이 둘뿐이다.
 *
 * <p>저참여가 3에 닿으면 이해 확인 프롬프트를, {@code UNMEASURABLE} 이 3에 닿으면 자세 안내 프롬프트를 띄울 조건이 된다. 서버는 같은 카운터로 학생 상태를 파생한다. 조작 규칙은
 * {@link DetectionRunTransition} 이 소유한다.
 *
 * @param lowEngagement 저참여 연속 횟수
 * @param unmeasurable {@code UNMEASURABLE} 연속 횟수
 */
public record DetectionRunCounters(int lowEngagement, int unmeasurable) {

    /** 프롬프트·상태 확정에 필요한 연속 횟수. */
    public static final int REQUIRED_RUN = 3;

    public static DetectionRunCounters none() {
        return new DetectionRunCounters(0, 0);
    }

    /** 저참여가 3연속에 닿았는지. 이해 확인 프롬프트를 띄울 조건이다(소비자는 티켓 85). */
    public boolean lowEngagementRunComplete() {
        return lowEngagement >= REQUIRED_RUN;
    }

    /** {@code UNMEASURABLE} 이 3연속에 닿았는지. 학생 상태 UNMEASURABLE 이 확정된다. */
    public boolean unmeasurableRunComplete() {
        return unmeasurable >= REQUIRED_RUN;
    }
}
