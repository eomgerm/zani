package com.a105.zani.session.application.presence;

import java.time.Clock;
import java.time.Duration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.SessionAlreadyEndedException;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.port.SessionPresencePort;
import com.a105.zani.session.domain.model.ConnectionState;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

/**
 * 세션 참가자 presence를 Redis TTL로 관리한다. heartbeat마다 presence를 갱신하고, 강사가 이탈하면 5분 유예를 시작한다. 유예가 만료된 뒤 도착한 heartbeat가 세션 종료를
 * 트리거한다(별도 스케줄러 없음). 시간 판단은 주입된 {@link Clock}을 따른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionPresenceService implements RecordPresenceUseCase {

    /** 강사 미복귀 유예 시간. */
    private static final Duration INSTRUCTOR_GRACE = Duration.ofMinutes(5);
    /** 접속 presence 키 TTL. heartbeat 주기보다 넉넉히 잡아 짧은 지연에도 접속으로 유지한다. */
    private static final Duration PRESENCE_TTL = Duration.ofSeconds(30);
    /** 유예 키를 마감 이후에도 잠시 유지해 만료 평가가 가능하도록 하는 버퍼. */
    private static final Duration GRACE_KEY_TTL = INSTRUCTOR_GRACE.plusMinutes(1);

    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository participantRepository;
    private final SessionPresencePort presencePort;
    private final Clock clock;

    @Override
    @Transactional
    public PresenceResult record(RecordPresenceCommand command) {
        // 멤버십을 먼저 확인해 비멤버에게 세션 존재·상태를 노출하지 않는다.
        SessionParticipant participant = participantRepository
                .findBySessionIdAndUserId(command.sessionId(), command.userId())
                .orElseThrow(NotSessionMemberException::new);

        Session session = sessionRepository.findById(command.sessionId()).orElseThrow(SessionNotFoundException::new);
        if (session.isEnded()) {
            throw new SessionAlreadyEndedException();
        }

        long sessionId = session.id();
        long participantId = participant.id();

        // 유예 만료 여부는 이번 heartbeat가 presence 상태를 바꾸기 전에 판단한다. 그래야 유예가 이미 지난 뒤
        // 강사가 뒤늦게 접속해도 자신의 heartbeat로 종료를 취소하지 못한다(수용 기준: 5분 미복귀 → 종료).
        if (isInstructorGraceExpired(sessionId)) {
            endSession(sessionId, session, participantId);
            return result(participant, command, ReconnectStatus.SESSION_ENDED, true);
        }

        boolean connected = command.connectionState() == ConnectionState.CONNECTED;
        ReconnectStatus reconnectStatus = applyPresence(sessionId, participantId, participant.role(), connected);
        return result(participant, command, reconnectStatus, false);
    }

    private ReconnectStatus applyPresence(
            long sessionId, long participantId, SessionParticipantRole role, boolean connected) {
        if (connected) {
            presencePort.recordHeartbeat(sessionId, participantId, PRESENCE_TTL);
            if (role == SessionParticipantRole.INSTRUCTOR
                    && presencePort.instructorGraceDeadline(sessionId).isPresent()) {
                presencePort.clearInstructorGrace(sessionId);
                return ReconnectStatus.RECONNECTED;
            }
            return ReconnectStatus.CONNECTED;
        }

        // RECONNECTING / DISCONNECTED: 현재 접속 상태가 아니다.
        presencePort.clearPresence(sessionId, participantId);
        if (role == SessionParticipantRole.INSTRUCTOR) {
            // 이미 진행 중인 유예가 있으면 마감 시각을 유지한다(반복 이탈로 유예가 리셋되지 않도록).
            if (presencePort.instructorGraceDeadline(sessionId).isEmpty()) {
                presencePort.startInstructorGrace(sessionId, clock.instant().plus(INSTRUCTOR_GRACE), GRACE_KEY_TTL);
            }
            return ReconnectStatus.GRACE_PERIOD;
        }
        return ReconnectStatus.DISCONNECTED;
    }

    /** 이번 heartbeat 시점 기준으로 강사 유예가 이미 지났는지. presence 변경 전에 읽은 마감 시각으로 판단한다. */
    private boolean isInstructorGraceExpired(long sessionId) {
        return presencePort
                .instructorGraceDeadline(sessionId)
                .map(deadline -> !clock.instant().isBefore(deadline))
                .orElse(false);
    }

    private void endSession(long sessionId, Session session, long participantId) {
        session.end();
        sessionRepository.save(session);
        presencePort.clearInstructorGrace(sessionId);
        presencePort.clearPresence(sessionId, participantId);
        log.info("Session {} ended: instructor did not return within the grace period", sessionId);
    }

    private PresenceResult result(
            SessionParticipant participant,
            RecordPresenceCommand command,
            ReconnectStatus reconnectStatus,
            boolean sessionEnded) {
        return new PresenceResult(
                participant.id(),
                participant.role(),
                command.connectionState(),
                command.heartbeatAt(),
                reconnectStatus,
                sessionEnded);
    }
}
