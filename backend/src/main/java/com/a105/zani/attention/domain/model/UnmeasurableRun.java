package com.a105.zani.attention.domain.model;

/**
 * {@code UNMEASURABLE} 관측이 연달아 몇 번 왔는지.
 *
 * <p>3연속에 닿으면 학생 상태 {@code UNMEASURABLE} 이 확정되고 집계 분자에 든다(§7.3). 한 건으로 넣지 않는 이유는 고개를 크게 돌리거나 자세를 고쳐 앉는 것만으로도 그 10초의
 * 검출률이 70%에 못 미치기 때문이다. 그걸 바로 분자에 넣으면 잠깐 몸을 움직인 학생이 이탈자로 계산된다.
 *
 * @param consecutive 연속 횟수
 */
public record UnmeasurableRun(int consecutive) {

    /** 학생 상태 확정에 필요한 연속 횟수. */
    public static final int REQUIRED_RUN = 3;

    public static UnmeasurableRun none() {
        return new UnmeasurableRun(0);
    }

    /** 3연속에 닿았는지. */
    public boolean isComplete() {
        return consecutive >= REQUIRED_RUN;
    }
}
