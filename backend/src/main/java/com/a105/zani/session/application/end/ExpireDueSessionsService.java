package com.a105.zani.session.application.end;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionRepository;

/**
 * 최대 수업 시간을 넘긴 세션을 찾아 종료한다. 종료 자체는 {@link EndSessionUseCase}가 수행하므로(가이드 §12의 단일 종료 경로), 이 유스케이스는 "무엇을 종료할지" 판단만 담당한다. 한
 * 번에 처리할 건수를 제한해 긴 트랜잭션·대량 처리를 피한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpireDueSessionsService implements ExpireDueSessionsUseCase {

    private static final int BATCH_SIZE = 50;

    private final SessionRepository sessionRepository;
    private final EndSessionUseCase endSessionUseCase;
    private final Clock clock;

    /** 세션 종료는 각 건이 독립 트랜잭션(EndSessionService)이라 여기서는 트랜잭션을 걸지 않는다. 한 건 실패가 나머지를 막지 않는다. */
    @Override
    public int expireDueSessions() {
        Instant cutoff = clock.instant().minus(Session.ACTIVE_DURATION);
        List<Session> due = sessionRepository.findLiveStartedBefore(cutoff, BATCH_SIZE);

        int ended = 0;
        for (Session session : due) {
            try {
                if (endSessionUseCase
                        .end(new EndSessionCommand(session.id(), SessionEndReason.MAX_DURATION_REACHED))
                        .ended()) {
                    ended++;
                }
            } catch (RuntimeException exception) {
                log.error("Failed to end expired session {}: {}", session.id(), exception.getMessage());
            }
        }
        if (ended > 0) {
            log.info("Ended {} session(s) that reached the maximum duration", ended);
        }
        return ended;
    }
}
