package com.a105.zani.session.application.end;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionEndReason;
import com.a105.zani.session.domain.repository.SessionRepository;

/**
 * 시간이 지나 스스로 정리돼야 하는 세션을 찾아 처리한다. "무엇을 정리할지" 판단만 하고, 종료 자체는 {@link EndSessionUseCase}가 수행한다(가이드 §12의 단일 종료 경로).
 *
 * <p>세 가지를 훑는다.
 *
 * <ul>
 *   <li>최대 수업 시간(3시간)을 넘긴 {@code LIVE} 세션 → 종료
 *   <li>시작되지 않은 채 방치된 {@code PREPARING} 세션 → 종료. 이걸 두면 강사의 활성 세션 잠금이 계속 잡혀 새 수업을 열지 못한다.
 *   <li>메모 마감이 지난 {@code NOTE_PENDING} 세션 → 최종 종료
 * </ul>
 *
 * <p>한 번에 처리할 건수를 제한해 긴 트랜잭션·대량 처리를 피한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpireDueSessionsService implements ExpireDueSessionsUseCase {

    private static final int BATCH_SIZE = 50;

    /** 강사가 방을 만들고 시작하지 않은 채 이 시간이 지나면 방치된 것으로 본다. 장비 점검에 걸리는 시간보다 넉넉해야 한다. */
    private static final Duration PREPARING_TIMEOUT = Duration.ofMinutes(30);

    private final SessionRepository sessionRepository;
    private final EndSessionUseCase endSessionUseCase;
    private final Clock clock;

    /** 세션 종료는 각 건이 독립 트랜잭션(EndSessionService)이라 여기서는 트랜잭션을 걸지 않는다. 한 건 실패가 나머지를 막지 않는다. */
    @Override
    public int expireDueSessions() {
        Instant now = clock.instant();

        int ended = endAll(
                sessionRepository.findLiveStartedBefore(now.minus(Session.ACTIVE_DURATION), BATCH_SIZE),
                SessionEndReason.MAX_DURATION_REACHED);
        ended += endAll(
                sessionRepository.findPreparingCreatedBefore(now.minus(PREPARING_TIMEOUT), BATCH_SIZE),
                SessionEndReason.ABANDONED_BEFORE_START);

        if (ended > 0) {
            log.info("Ended {} session(s) that were due", ended);
        }
        closeDueNoteWindows(now);
        return ended;
    }

    private int endAll(List<Session> due, SessionEndReason reason) {
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
                log.error("Failed to end session {} (reason={})", session.id(), reason, exception);
            }
        }
        return ended;
    }

    /** 메모 마감이 지난 세션을 최종 종료로 넘긴다. 이미 종료 절차를 마친 세션이라 EndSessionUseCase를 다시 태우지 않는다. */
    private void closeDueNoteWindows(Instant now) {
        for (Session session : sessionRepository.findNotePendingDueBefore(now, BATCH_SIZE)) {
            try {
                if (session.closeNoteWindow(now)) {
                    sessionRepository.save(session);
                }
            } catch (RuntimeException exception) {
                log.error("Failed to close the note window of session {}", session.id(), exception);
            }
        }
    }
}
