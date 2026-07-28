package com.a105.zani.coach.application.checkavailability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.a105.zani.coach.application.port.CoachingAvailabilityPort;
import com.a105.zani.coach.application.port.GmsHealthPort;

/**
 * 수업 시작 시 GMS 도달 가능성을 확인해 코칭 가용 여부를 판정하고 저장한다. (S15P11A105-202)
 *
 * <p>헬스체크 실패는 코칭 비활성(false)으로 흡수하며, 수업 진행을 막지 않는다. Redis 만 사용하는 기술 상태 변경이라 트랜잭션을 걸지 않는다. (ddd-development-guide §6.1)
 */
@Service
public class CoachingAvailabilityService implements CheckCoachingAvailabilityUseCase {

    private static final Logger log = LoggerFactory.getLogger(CoachingAvailabilityService.class);

    private final GmsHealthPort gmsHealthPort;
    private final CoachingAvailabilityPort coachingAvailabilityPort;

    public CoachingAvailabilityService(GmsHealthPort gmsHealthPort, CoachingAvailabilityPort coachingAvailabilityPort) {
        this.gmsHealthPort = gmsHealthPort;
        this.coachingAvailabilityPort = coachingAvailabilityPort;
    }

    @Override
    public boolean check(CheckCoachingAvailabilityCommand command) {
        boolean available;
        try {
            available = gmsHealthPort.isGmsReachable();
        } catch (RuntimeException exception) {
            log.warn("coaching health check failed for session {}: {}", command.sessionId(), exception.toString());
            available = false;
        }
        coachingAvailabilityPort.store(command.sessionId(), available);
        return available;
    }
}
