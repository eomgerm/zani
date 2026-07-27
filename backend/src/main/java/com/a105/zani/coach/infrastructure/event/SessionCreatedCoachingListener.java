package com.a105.zani.coach.infrastructure.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.a105.zani.coach.application.checkavailability.CheckCoachingAvailabilityCommand;
import com.a105.zani.coach.application.checkavailability.CheckCoachingAvailabilityUseCase;
import com.a105.zani.session.application.create.SessionCreatedEvent;

/**
 * 수업 생성 이벤트를 받아 코칭 가용 헬스체크를 비동기로 1회 실행한다. 헬스체크 실패는 로그만 남기고 삼켜, 수업 생성 흐름을 절대 막지 않는다. (S15P11A105-202)
 *
 * <p>이벤트 배선(Spring)은 infrastructure 에 두어 application 을 프레임워크로부터 보호한다.
 */
@Component
public class SessionCreatedCoachingListener {

    private static final Logger log = LoggerFactory.getLogger(SessionCreatedCoachingListener.class);

    private final CheckCoachingAvailabilityUseCase checkCoachingAvailabilityUseCase;

    public SessionCreatedCoachingListener(CheckCoachingAvailabilityUseCase checkCoachingAvailabilityUseCase) {
        this.checkCoachingAvailabilityUseCase = checkCoachingAvailabilityUseCase;
    }

    @Async
    @EventListener
    public void onSessionCreated(SessionCreatedEvent event) {
        try {
            checkCoachingAvailabilityUseCase.check(new CheckCoachingAvailabilityCommand(event.sessionId()));
        } catch (RuntimeException exception) {
            log.warn("coaching availability check failed for session {}: {}", event.sessionId(), exception.toString());
        }
    }
}
