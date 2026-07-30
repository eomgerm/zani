package com.a105.zani.attention.application.port;

import java.time.Duration;
import java.util.Optional;

/**
 * 코칭 트리거의 진행 상태를 보관하는 포트.
 *
 * <p>쿨타임·중복 방지·팁 결과를 <b>하나의 상태</b>로 다룬다. 열린 트리거가 있다는 것이 곧 쿨타임 중이라는 뜻이고, 그 트리거의 결과가 강사가 폴링으로 받아 갈 팁이다. 셋을 따로 두면 쿨타임은
 * 끝났는데 결과가 남아 있는 조합이 생겨, 강사가 10분 전 팁을 새 팁으로 받는다.
 */
public interface CoachingTriggerStatePort {

    /**
     * 트리거를 열고 그 순간부터 쿨타임을 시작한다. 이미 열려 있으면 아무것도 하지 않는다.
     *
     * <p>여는 것과 쿨타임 시작이 한 연산이어야 한다. 나눠 두면 동시 폴링 두 건이 모두 "열려 있지 않다"를 보고 트리거를 두 번 만들어, 같은 구간을 두 번 전사하고 팁을 두 번 띄운다.
     *
     * <p>결과와 무관하게 쿨타임이 시작되는 것이 의도다. 성공 시에만 시작하면 GMS 가 느리거나 크레딧이 소진됐을 때 비율이 임계 이상인 동안 10초마다 재시도해 크레딧을 태운다.
     *
     * @return 이 호출이 트리거를 열었으면 {@code true}, 이미 열려 있었으면 {@code false}
     */
    boolean openTrigger(long sessionId, String triggerId, Duration cooldown);

    /** 지금 열려 있는 트리거의 결과. 쿨타임이 끝났으면 비어 있다. */
    Optional<CoachingOutcome> openOutcome(long sessionId);

    /**
     * 팁 생성이 끝난 결과를 채운다. 쿨타임은 늘리지 않는다.
     *
     * <p>쿨타임이 이미 끝났으면 쓰지 않는다. 되살리면 10분 지난 팁이 새 팁으로 강사에게 표시된다.
     */
    void completeOutcome(long sessionId, CoachingOutcome outcome);

    /** 직전에 보여준 팁. 한 수업 안에서는 쿨타임이 끝나도 남는다. */
    Optional<PreviousCoachingTip> previousTip(long sessionId);
}
