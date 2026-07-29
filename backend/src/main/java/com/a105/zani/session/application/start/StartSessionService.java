package com.a105.zani.session.application.start;

import java.time.Clock;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.application.exception.SessionAlreadyEndedException;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.model.SessionStatusChange;
import com.a105.zani.session.domain.repository.SessionRepository;
import com.a105.zani.session.domain.repository.SessionStatusChangeRepository;

/**
 * 강사가 준비를 마치고 수업을 실제로 시작한다. 이 전이가 있어야 초대 코드가 유효해지고 최대 수업 시간이 흐르기 시작한다(가이드 §5).
 *
 * <p>가이드 §7은 시작 전에 RoomService로 강사 참가자와 마이크 Track을 확인하고 Egress를 띄우라고 규정하지만, 그 연동은 아직 없다. 지금은 상태 전이만 수행하며, 확인 절차가 붙으면 이
 * 유스케이스 안에서 전이 앞에 들어간다.
 *
 * <p>멱등하다. 이미 진행 중인 세션에 다시 요청해도 시작 시각이 뒤로 밀리지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StartSessionService implements StartSessionUseCase {

    private final SessionRepository sessionRepository;
    private final SessionStatusChangeRepository statusChangeRepository;
    private final Clock clock;

    @Override
    @Transactional
    public StartSessionResult start(StartSessionCommand command) {
        Session session = sessionRepository.findById(command.sessionId()).orElseThrow(SessionNotFoundException::new);
        if (!session.instructorId().equals(command.userId())) {
            log.warn("Non-instructor {} tried to start session {}", command.userId(), command.sessionId());
            throw new NotSessionInstructorException();
        }
        // 종료 절차에 들어간 세션은 다시 시작할 수 없다. 도메인도 막지만, 여기서 걸러야 409로 응답한다.
        if (session.hasStartedEnding()) {
            throw new SessionAlreadyEndedException();
        }

        Instant now = clock.instant();
        if (!session.markLive(now)) {
            return result(session, false);
        }
        statusChangeRepository.append(new SessionStatusChange(
                TsidGenerator.generate(), session.id(), SessionStatus.PREPARING, SessionStatus.LIVE, now));
        Session started = sessionRepository.save(session);
        log.info("Session {} started at {}", started.id(), now);
        return result(started, true);
    }

    private StartSessionResult result(Session session, boolean started) {
        return new StartSessionResult(
                session.id(),
                session.status(),
                session.inviteCode(),
                session.expiresAt().orElse(null),
                started);
    }
}
