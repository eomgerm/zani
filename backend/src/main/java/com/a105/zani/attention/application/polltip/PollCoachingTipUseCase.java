package com.a105.zani.attention.application.polltip;

/**
 * 강사의 팁 폴링을 처리하는 유스케이스(확정 문서 §7).
 *
 * <p>폴링이 트리거 판정을 겸한다. 판정 이벤트마다(학생 수 × 10초) 평가하면 같은 순간을 여러 번 판정하게 되고 학생의 요청 스레드에 코칭 비용이 붙는다. 강사의 폴링은 세션당 10초에 한 번이고 팁을
 * 받을 당사자가 부르므로, 판정을 여기에 두면 주기가 자연히 한 번으로 맞는다.
 */
public interface PollCoachingTipUseCase {

    PollCoachingTipResult poll(PollCoachingTipCommand command);
}
