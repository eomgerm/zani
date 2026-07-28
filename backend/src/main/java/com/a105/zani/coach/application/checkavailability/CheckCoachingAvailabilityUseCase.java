package com.a105.zani.coach.application.checkavailability;

public interface CheckCoachingAvailabilityUseCase {

    /**
     * GMS 도달 가능성을 1회 확인하고 세션별 코칭 가용 상태를 저장한다.
     *
     * <p>확인은 설정된 health-model 로 최소 프로브를 보내는 방식이며, whisper-1(203)·팁 모델(204)의 개별 가용성은 확인하지 않는다.
     *
     * @return 저장된 가용 여부(확인 실패 시 false)
     */
    boolean check(CheckCoachingAvailabilityCommand command);
}
