package com.a105.zani.session.application.presence;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.session.application.end.EndSessionCommand;
import com.a105.zani.session.application.end.EndSessionUseCase;
import com.a105.zani.session.application.end.SessionEndReason;
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
    /** 유예 마커의 Redis TTL. 세션이 살아있는 동안(최대 활성 시간) 유지해, 늦거나 드문 heartbeat가 유예 만료 이후 도착하더라도 종료 판단 근거(마감 시각)가 소실되지 않도록 한다. */
    private static final Duration GRACE_KEY_TTL = Session.ACTIVE_DURATION;

    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository participantRepository;
    private final SessionPresencePort presencePort;
    private final EndSessionUseCase endSessionUseCase;
    private final Clock clock;

    // 흔한 heartbeat 경로는 Redis 읽기·쓰기뿐이라 DB 트랜잭션으로 감싸지 않는다(잦은 heartbeat가 Redis 지연 동안
    // DB 커넥션을 점유하지 않도록). 유일한 DB 쓰기인 endSession의 session 저장은 saveAndFlush로 그 자체가 원자적이다.
    @Override
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
            endSession(sessionId, SessionEndReason.INSTRUCTOR_ABSENT);
            return result(participant, command, ReconnectStatus.SESSION_ENDED, true);
        }

        ReconnectStatus reconnectStatus =
                applyPresence(sessionId, participantId, participant.role(), command.connectionState());

        // 마지막 사람이 나갔으면 수업을 붙잡고 있을 이유가 없다(LIVE-010). DISCONNECTED 보고에서만 확인한다 —
        // 접속 중인 heartbeat 는 방금 자기 presence 를 심었으므로 빈 방일 수 없고, RECONNECTING 은 복구를
        // 시도 중인 일시 상태라 여기서 끝내면 마지막 참가자가 순간 끊김 한 번에 수업을 잃는다(강사 부재의
        // 5분 유예와 달리 유예가 전혀 없다). FE 는 복구를 포기하는 시점에 DISCONNECTED 를 따로 보고하므로
        // (useSessionPresence) 진짜 이탈은 그때 잡힌다.
        //
        // 강사 유예 중에는 비어 있어도 끝내지 않는다. 유예는 강사가 돌아올 시간을 주자는 것인데, 여기서 바로
        // 종료하면 혼자 준비 중이던 강사가 새로고침 한 번에 수업을 잃는다 — 5분 유예(LIVE-009)가 통째로
        // 무력해진다. 강사가 끝내 돌아오지 않으면 유예 만료가 같은 자리에서 종료를 집는다.
        boolean departed = command.connectionState() == ConnectionState.DISCONNECTED;
        if (departed && !isInstructorGraceRunning(sessionId) && isSessionEmpty(sessionId, participantId)) {
            endSession(sessionId, SessionEndReason.ALL_PARTICIPANTS_LEFT);
            return result(participant, command, ReconnectStatus.SESSION_ENDED, true);
        }
        return result(participant, command, reconnectStatus, false);
    }

    private ReconnectStatus applyPresence(
            long sessionId, long participantId, SessionParticipantRole role, ConnectionState state) {
        if (state == ConnectionState.CONNECTED) {
            presencePort.recordHeartbeat(sessionId, participantId, clock.instant(), PRESENCE_TTL);
            if (role == SessionParticipantRole.INSTRUCTOR
                    && presencePort.instructorGraceDeadline(sessionId).isPresent()) {
                presencePort.clearInstructorGrace(sessionId);
                return ReconnectStatus.RECONNECTED;
            }
            // 학생에게도 강사 유예를 알린다. 강사가 끊긴 동안 수업이 곧 자동 종료된다는 것을 학생 화면이
            // 보여주려면(SessionPresenceNotice) 이 신호가 필요하다 — 유예를 강사에게만 돌려주면
            // 학생은 아무 안내 없이 수업이 끝나는 것을 본다.
            if (presencePort.instructorGraceDeadline(sessionId).isPresent()) {
                return ReconnectStatus.GRACE_PERIOD;
            }
            return ReconnectStatus.CONNECTED;
        }

        // RECONNECTING / DISCONNECTED: 현재 접속 상태가 아니다. 두 경우 모두 presence 를 지운다 —
        // 재연결 구간은 참여 판정을 만들지 않아 집계 분모에서 빠져야 한다(FRD §11.6: 연결 불가는 분자에
        // 들어갈 수 없다. 분모에만 남으면 이탈이 늘수록 트리거가 안 뜬다).
        presencePort.clearPresence(sessionId, participantId);
        if (state == ConnectionState.RECONNECTING) {
            // 집계에서는 빠지지만 방을 나간 것은 아니다. 빈 방 판정이 이 사람을 볼 수 있게 따로 표시한다.
            // 표시가 없으면 A 가 복구하는 동안 B 가 탭을 닫는 것만으로 수업이 끝나 A 는 돌아올 방을 잃는다.
            // TTL 은 presence 와 같다 — heartbeat 주기보다 넉넉해, 복구 중 heartbeat 마다 갱신된다.
            presencePort.markReconnecting(sessionId, participantId, PRESENCE_TTL);
        }
        if (role == SessionParticipantRole.INSTRUCTOR) {
            // 유예 시작은 포트가 원자적으로 "없을 때만" 처리한다(반복·동시 이탈로 마감 시각이 갱신되지 않도록).
            presencePort.startInstructorGrace(sessionId, clock.instant().plus(INSTRUCTOR_GRACE), GRACE_KEY_TTL);
            return ReconnectStatus.GRACE_PERIOD;
        }
        return ReconnectStatus.DISCONNECTED;
    }

    /** 강사 복귀를 기다리는 중인지. 유예가 도는 동안은 방이 비어도 종료하지 않는다. */
    private boolean isInstructorGraceRunning(long sessionId) {
        return presencePort.instructorGraceDeadline(sessionId).isPresent();
    }

    /**
     * 이 세션에 남아 있는 참가자가 하나도 없는지.
     *
     * <p>참가자 후보를 DB 에서 받아 그중 presence 키가 살아 있는 사람을 센다 — Redis 키 공간을 훑지 않기
     * 위해서다({@link SessionPresencePort#connectedSince} 의 계약).
     *
     * <p><b>복구 중인 사람도 남아 있는 것으로 본다.</b> 그 사람은 집계 분모에서는 빠지지만 방을 나간 것은 아니다. 여기서 빼면 A 가 복구하는 동안 B 가 탭을 닫는 것만으로 수업이 끝나고, A
     * 는 돌아왔을 때 종료된 세션을 만난다.
     *
     * @param reportingParticipantId 방금 이탈을 보고한 참가자. 이 사람의 복구 표시는 무시한다 — 조금 전 RECONNECTING 이었다가 이제 포기한 경우 그 표시가 아직 남아
     *     있어, 그대로 두면 마지막 사람의 이탈이 자기 표시에 막혀 수업이 3시간 만료까지 살아 있게 된다
     */
    private boolean isSessionEmpty(long sessionId, long reportingParticipantId) {
        List<Long> participantIds = participantRepository.findBySessionId(sessionId).stream()
                .map(SessionParticipant::id)
                .toList();
        if (!presencePort.connectedSince(sessionId, participantIds).isEmpty()) {
            return false;
        }
        List<Long> others = participantIds.stream()
                .filter(id -> id != reportingParticipantId)
                .toList();
        return !presencePort.anyReconnecting(sessionId, others);
    }

    /** 이번 heartbeat 시점 기준으로 강사 유예가 이미 지났는지. presence 변경 전에 읽은 마감 시각으로 판단한다. */
    private boolean isInstructorGraceExpired(long sessionId) {
        return presencePort
                .instructorGraceDeadline(sessionId)
                .map(deadline -> !clock.instant().isBefore(deadline))
                .orElse(false);
    }

    private void endSession(long sessionId, SessionEndReason reason) {
        // 종료는 단일 유스케이스로만 수행한다(가이드 §12: 강사 명시 종료·3시간·강사 미복귀가 같은 경로).
        // DB의 ENDED 상태가 유일한 진실이다. 종료 이후 heartbeat는 위의 isEnded 가드에서 409로 막혀 유예를 다시 평가하지 않으므로,
        // 남은 유예·presence 키는 그대로 두어도 무해하며 각자의 TTL로 자연 소멸한다.
        endSessionUseCase.end(new EndSessionCommand(sessionId, reason));
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
