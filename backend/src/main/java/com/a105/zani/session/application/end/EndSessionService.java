package com.a105.zani.session.application.end;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.audioclip.application.releaseaudio.ReleaseInstructorAudioUseCase;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.port.SessionActivationLockPort;
import com.a105.zani.session.application.port.SessionStatusHistoryPort;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.repository.SessionRepository;

/**
 * 수업 종료. 강사 명시 종료·3시간 자동 종료·강사 5분 미복귀가 모두 이 유스케이스를 쓴다(가이드 §12의 단일 종료 경로).
 *
 * <p>확정 흐름의 LIVE → ENDING → NOTE_PENDING 을 이 안에서 모두 밟는다. 다만 <b>두 전이가 한 트랜잭션에서 함께 커밋되므로 {@code sessions} 행이 ENDING 으로
 * 보이는 순간은 없다.</b> 다른 요청이 읽는 값은 LIVE 아니면 NOTE_PENDING 이다. ENDING 을 거치는 값은 전이 이력 ({@code session_status_changes})에 남아,
 * 수업이 끝난 뒤 리포트가 타임라인을 다시 세울 때 "언제 정리에 들어갔는지"를 알 수 있다.
 *
 * <p>그래서 정리 도중에 들어온 입장은 막히지 않는다. 이 트랜잭션이 커밋되기 전이라 그 요청은 아직 LIVE 를 읽는다. 다음 heartbeat 가 409 를 받아 학생이 강의실에서 나가므로 스스로 교정되고,
 * 완전히 막으려면 종료도 입장과 같은 행 잠금을 잡아야 하는데 창이 트랜잭션 하나 길이라 그만한 값을 하지 않는다.
 *
 * <p>멱등하다. 이미 종료 절차에 들어간 세션에 대한 재요청은 상태를 건드리지 않고 {@code ended=false} 로 답한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EndSessionService implements EndSessionUseCase {

    private final SessionRepository sessionRepository;
    private final ReleaseInstructorAudioUseCase releaseInstructorAudioUseCase;
    private final SessionActivationLockPort activationLockPort;
    private final SessionStatusHistoryPort statusHistoryPort;
    private final Clock clock;

    @Override
    @Transactional
    public EndSessionResult end(EndSessionCommand command) {
        Session session = sessionRepository.findById(command.sessionId()).orElseThrow(SessionNotFoundException::new);

        SessionStatus beforeEnding = session.status();
        // DATETIME(6) 이 마이크로초까지만 담는다. 나노초를 그대로 쓰면 저장 전후의 값이 달라진다.
        Instant endedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!session.beginEnding(endedAt, command.reason())) {
            // 이미 종료 절차에 들어간 세션은 그대로 둔다(중복 종료 요청·재시도에 멱등).
            return new EndSessionResult(session.id(), session.status(), false);
        }
        statusHistoryPort.record(session.id(), beforeEnding, session.status(), endedAt);

        releaseInstructorAudio(session.id());
        releaseActivationLock(session.instructorId());

        if (session.awaitNote()) {
            statusHistoryPort.record(session.id(), SessionStatus.ENDING, session.status(), endedAt);
        }
        Session ended = sessionRepository.save(session);
        log.info("Session {} ended: reason={}, status={}", ended.id(), command.reason(), ended.status());
        return new EndSessionResult(ended.id(), ended.status(), true);
    }

    /**
     * 코칭 오디오 버퍼를 반납한다. 세션당 수십 MB를 잡고 있어 종료 시 놓아야 한다.
     *
     * <p>실패해도 종료를 되돌리지 않는다. 메모리 조작뿐이라 되돌릴 것이 없고, 롤백하면 수업이 계속 살아 있는 것으로 남는다 — 그쪽이 더 나쁘다. 이 트랜잭션 안에서 예외가 그대로 올라가면 정확히 그
     * 롤백이 일어나므로 여기서 흡수한다.
     */
    private void releaseInstructorAudio(long sessionId) {
        try {
            releaseInstructorAudioUseCase.release(sessionId);
        } catch (RuntimeException exception) {
            log.warn("Failed to release the coaching audio buffer for session {}", sessionId, exception);
        }
    }

    /**
     * 다음 수업을 열 수 있도록 활성 잠금을 반납한다.
     *
     * <p>잠금은 3시간 TTL 이라, 반납하지 않으면 수업을 끝낸 강사가 그 시간 동안 "이미 진행 중인 수업이 있어요" 로 막힌다. 종료가 곧 다음 수업을 열 수 있게 되는 시점이다.
     *
     * <p>실패해도 종료를 되돌리지 않는다. 잠금이 남는 최악의 경우는 TTL 만큼 기다리면 풀리지만, 종료를 롤백하면 수업이 계속 살아 있는 것으로 남는다 — 그쪽이 더 나쁘다.
     */
    private void releaseActivationLock(long instructorId) {
        try {
            activationLockPort.release(instructorId);
        } catch (RuntimeException exception) {
            log.warn("Failed to release the activation lock for instructor {}", instructorId, exception);
        }
    }
}
