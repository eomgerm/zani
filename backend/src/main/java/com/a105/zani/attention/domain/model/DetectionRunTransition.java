package com.a105.zani.attention.domain.model;

/**
 * 관측 한 건이 {@code UNMEASURABLE} 연속 카운터에 가하는 조작.
 *
 * <p>이 판단이 §7.3을 구현한 <b>유일한 자리</b>다. 저장소는 여기서 나온 조작을 원자적으로 적용하기만 한다.
 *
 * <p>서버가 세는 연속 카운터는 이 하나뿐이다. 저참여 연속은 브라우저가 센다 — 이해 확인 프롬프트를 띄울지 판정하는 주체가 브라우저이고(§5, 티켓 75·81), 저참여는 학생 상태가 아니어서 집계 분자에도
 * 들어가지 않는다(§2·§7.3).
 *
 * @param unmeasurable {@code UNMEASURABLE} 카운터에 가할 조작
 */
public record DetectionRunTransition(RunStep unmeasurable) {

    /**
     * 관측 한 건에 대한 전이.
     *
     * <p>{@code UNMEASURABLE} 이 아닌 출력은 관측이 실제로 됐다는 뜻이므로 구간을 끊는다. 카메라가 꺼진 경우도 마찬가지다 — 그쪽은 분모에서 빼는 판단으로 따로 다룬다(§7.1).
     */
    public static DetectionRunTransition of(DetectorOutcome outcome) {
        return new DetectionRunTransition(outcome == DetectorOutcome.UNMEASURABLE ? RunStep.INCREMENT : RunStep.RESET);
    }
}
