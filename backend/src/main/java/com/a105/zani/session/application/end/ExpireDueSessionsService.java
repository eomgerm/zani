package com.a105.zani.session.application.end;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionEndReason;
import com.a105.zani.session.domain.repository.SessionRepository;

/**
 * 스스로 끝나지 않는 세션을 찾아 종료한다. 종료 자체는 {@link EndSessionUseCase}가 수행하므로(가이드 §12의 단일 종료 경로), 이 유스케이스는 "무엇을 종료할지" 판단만 담당한다. 한
 * 번에 처리할 건수를 제한해 긴 트랜잭션·대량 처리를 피한다.
 *
 * <p>대상이 두 종류다. 진행 중인데 최대 수업 시간을 넘긴 세션과, 만들어 놓고 시작하지 않은 채 방치된 세션이다. 뒤쪽을 함께 보는 이유는 준비 중인 세션에 시작 시각이 없어 최대 수업 시간 기준으로는
 * 영원히 걸리지 않기 때문이다. 그대로 두면 강사가 생성만 하고 창을 닫은 수업이 홈 배너에 계속 "아직 끝내지 않은 수업"으로 남는다.
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

        int ended = endEach(
                sessionRepository.findLiveStartedBefore(cutoff, BATCH_SIZE), SessionEndReason.MAX_DURATION_REACHED);
        // 방치된 준비 세션은 같은 창(최대 수업 시간)을 쓴다. 활성 잠금 TTL 과 같아, 잠금이 풀릴 때 세션도 함께 정리된다.
        ended += endEach(
                sessionRepository.findPreparingCreatedBefore(cutoff, BATCH_SIZE),
                SessionEndReason.ABANDONED_BEFORE_START);

        if (ended > 0) {
            log.info("Ended {} session(s) that could not end on their own", ended);
        }
        return ended;
    }

    private int endEach(List<Session> due, SessionEndReason reason) {
        int ended = 0;
        for (Session session : due) {
            try {
                if (endSessionUseCase
                        .end(new EndSessionCommand(session.id(), reason))
                        .ended()) {
                    ended++;
                }
            } catch (RuntimeException exception) {
                // 예외 객체를 함께 넘겨 스택트레이스를 남긴다. 한 건 실패의 원인을 로그만으로 추적할 수 있어야 한다.
                log.error("Failed to end session {} for reason {}", session.id(), reason, exception);
            }
        }
        return ended;
    }
}
