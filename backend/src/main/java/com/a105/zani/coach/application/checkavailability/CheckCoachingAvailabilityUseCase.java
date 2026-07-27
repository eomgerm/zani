package com.a105.zani.coach.application.checkavailability;

public interface CheckCoachingAvailabilityUseCase {

    /**
     * whisper-1 헬스체크를 1회 수행하고 세션별 코칭 가용 상태를 저장한다.
     *
     * @return 저장된 가용 여부(헬스체크 실패 시 false)
     */
    boolean check(CheckCoachingAvailabilityCommand command);
}
