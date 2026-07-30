package com.a105.zani.session.application.start;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.port.SessionStatusHistoryPort;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.repository.SessionRepository;

/**
 * 준비된 수업을 실제로 시작한다. PREPARING → LIVE 전이가 초대 코드를 유효하게 만들고 3시간 자동 종료 시계를 켠다.
 *
 * <p>LiveKit room 을 따로 만들지 않는다. room 은 첫 참가자가 토큰으로 접속할 때 미디어 서버가 알아서 만들고, 강사는 준비 단계에서 이미 접속해 있다. 여기서 CreateRoom 을 부르면
 * 같은 방을 두 번 만드는 셈이고 실패 지점만 늘어난다.
 *
 * <p>멱등하다. 같은 요청이 두 번 와도 두 번째는 시작 시각을 밀지 않는다 — 시작 시각이 만료 시각의 기준이라, 밀리면 수업이 예정보다 늦게 끝난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StartSessionService implements StartSessionUseCase {

    private final SessionRepository sessionRepository;
    private final SessionStatusHistoryPort statusHistoryPort;
    private final Clock clock;

    @Override
    @Transactional
    public StartSessionResult start(StartSessionCommand command) {
        // 잠금을 걸고 읽는다. 잠금 없이 읽으면 동시에 들어온 종료와 각자 전이해, 늦게 커밋한 시작이 종료를
        // 덮어써 끝난 수업이 되살아난다. 동시 시작 두 건이 모두 started=true 가 되는 것도 같은 이유다.
        Session session =
                sessionRepository.findByIdForUpdate(command.sessionId()).orElseThrow(SessionNotFoundException::new);
        if (!session.instructorId().equals(command.userId())) {
            log.warn("Non-instructor {} tried to start session {}", command.userId(), command.sessionId());
            throw new NotSessionInstructorException();
        }

        SessionStatus previous = session.status();
        // DATETIME(6) 이 마이크로초까지만 담는다. 나노초를 그대로 쓰면 이 응답의 expiresAt 과
        // 다시 읽은 뒤의 expiresAt 이 달라져, 멱등한 재호출이 다른 만료 시각을 돌려준다.
        boolean started = session.start(clock.instant().truncatedTo(ChronoUnit.MICROS));
        if (!started) {
            return result(session, false);
        }

        Session saved = sessionRepository.save(session);
        statusHistoryPort.record(saved.id(), previous, saved.status(), saved.startedAt());
        log.info("Session {} started at {}", saved.id(), saved.startedAt());
        return result(saved, true);
    }

    private StartSessionResult result(Session session, boolean started) {
        return new StartSessionResult(
                session.id(), session.inviteCode(), session.status(), session.expiresAt(), started);
    }
}
