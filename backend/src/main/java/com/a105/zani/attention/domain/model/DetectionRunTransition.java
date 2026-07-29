package com.a105.zani.attention.domain.model;

/**
 * 관측 한 건이 두 연속 카운터에 가하는 조작(확정 문서 §4.1의 전이표).
 *
 * <p>이 표가 이 프로젝트에서 §4.1을 구현한 <b>유일한 자리</b>다. 저장소는 여기서 나온 조작을 원자적으로 적용하기만 한다.
 *
 * @param lowEngagement 저참여 카운터에 가할 조작
 * @param unmeasurable {@code UNMEASURABLE} 카운터에 가할 조작
 */
public record DetectionRunTransition(RunStep lowEngagement, RunStep unmeasurable) {

    /**
     * 관측 한 건에 대한 전이.
     *
     * <p>{@code UNMEASURABLE} 이 끼었을 때만 저참여 카운터를 유지한다. 관측이 비었다는 뜻일 뿐 학생이 갑자기 집중하기 시작했다는 증거가 아니기 때문이다. 집중하지 않는 학생일수록 자세가
     * 흐트러져 얼굴 검출도 같이 실패하는데, 그때마다 0으로 되돌리면 정작 도움이 필요한 학생이 프롬프트를 덜 받는다.
     *
     * <p>반대 방향은 되돌린다. {@code UNMEASURABLE} 이 도는 중에 4단계 값이 나왔다면 관측이 실제로 됐다는 확실한 증거다.
     */
    public static DetectionRunTransition of(DetectionSignal signal) {
        if (signal.outcome() == DetectorOutcome.UNMEASURABLE) {
            return new DetectionRunTransition(RunStep.KEEP, RunStep.INCREMENT);
        }
        if (signal.countsAsLowEngagement()) {
            return new DetectionRunTransition(RunStep.INCREMENT, RunStep.RESET);
        }
        return new DetectionRunTransition(RunStep.RESET, RunStep.RESET);
    }
}
