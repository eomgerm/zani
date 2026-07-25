package com.a105.zani.session.application.presence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.SessionAlreadyEndedException;
import com.a105.zani.session.application.port.SessionPresencePort;
import com.a105.zani.session.domain.model.ConnectionState;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionAnalysisStatus;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionPresenceServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long INSTRUCTOR_USER = 7L;
    private static final long STUDENT_USER = 8L;
    private static final long INSTRUCTOR_PARTICIPANT = 1L;
    private static final long STUDENT_PARTICIPANT = 2L;
    private static final Instant T0 = Instant.parse("2026-07-24T00:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final InMemoryPresencePort presencePort = new InMemoryPresencePort();
    private final FakeSessionRepository sessionRepository = new FakeSessionRepository();
    private final FakeParticipantRepository participantRepository = new FakeParticipantRepository();

    private SessionPresenceService service;

    @BeforeEach
    void setUp() {
        service = new SessionPresenceService(sessionRepository, participantRepository, presencePort, clock);
        sessionRepository.session = liveSession();
        participantRepository.byUserId.put(
                INSTRUCTOR_USER,
                SessionParticipant.reconstitute(
                        INSTRUCTOR_PARTICIPANT,
                        SESSION_ID,
                        INSTRUCTOR_USER,
                        SessionParticipantRole.INSTRUCTOR,
                        T0,
                        T0));
        participantRepository.byUserId.put(
                STUDENT_USER,
                SessionParticipant.reconstitute(
                        STUDENT_PARTICIPANT, SESSION_ID, STUDENT_USER, SessionParticipantRole.STUDENT, T0, T0));
    }

    private static Session liveSession() {
        return Session.reconstitute(
                SESSION_ID,
                INSTRUCTOR_USER,
                "제목",
                "INVITE01",
                false,
                T0,
                SessionStatus.LIVE,
                SessionAnalysisStatus.NOT_STARTED);
    }

    private PresenceResult heartbeat(long userId, ConnectionState state) {
        return service.record(new RecordPresenceCommand(SESSION_ID, userId, clock.instant(), state));
    }

    @Test
    void throwsForbiddenWhenTheUserIsNotASessionMember() {
        assertThrows(NotSessionMemberException.class, () -> heartbeat(999L, ConnectionState.CONNECTED));
    }

    @Test
    void throwsConflictWhenTheSessionHasAlreadyEnded() {
        sessionRepository.session.end();
        assertThrows(SessionAlreadyEndedException.class, () -> heartbeat(STUDENT_USER, ConnectionState.CONNECTED));
    }

    @Test
    void recordsPresenceAndReportsConnectedForAConnectedInstructor() {
        PresenceResult result = heartbeat(INSTRUCTOR_USER, ConnectionState.CONNECTED);

        assertEquals(ReconnectStatus.CONNECTED, result.reconnectStatus());
        assertFalse(result.sessionEnded());
        assertTrue(presencePort.presence.contains(SESSION_ID + ":" + INSTRUCTOR_PARTICIPANT));
    }

    @Test
    void startsTheFiveMinuteGraceAndReportsGracePeriodWhenTheInstructorDrops() {
        PresenceResult result = heartbeat(INSTRUCTOR_USER, ConnectionState.DISCONNECTED);

        assertEquals(ReconnectStatus.GRACE_PERIOD, result.reconnectStatus());
        assertFalse(result.sessionEnded());
        assertEquals(Optional.of(T0.plus(Duration.ofMinutes(5))), presencePort.instructorGraceDeadline(SESSION_ID));
    }

    @Test
    void clearsTheGraceAndReportsReconnectedWhenTheInstructorReturnsInTime() {
        heartbeat(INSTRUCTOR_USER, ConnectionState.DISCONNECTED);
        clock.setInstant(T0.plus(Duration.ofMinutes(2)));

        PresenceResult result = heartbeat(INSTRUCTOR_USER, ConnectionState.CONNECTED);

        assertEquals(ReconnectStatus.RECONNECTED, result.reconnectStatus());
        assertFalse(result.sessionEnded());
        assertTrue(presencePort.instructorGraceDeadline(SESSION_ID).isEmpty());
    }

    @Test
    void endsTheSessionOnTheFirstHeartbeatAfterTheGraceExpires() {
        heartbeat(INSTRUCTOR_USER, ConnectionState.DISCONNECTED);
        clock.setInstant(T0.plus(Duration.ofMinutes(6)));

        PresenceResult result = heartbeat(STUDENT_USER, ConnectionState.CONNECTED);

        assertEquals(ReconnectStatus.SESSION_ENDED, result.reconnectStatus());
        assertTrue(result.sessionEnded());
        assertTrue(sessionRepository.session.isEnded());
        assertEquals(1, sessionRepository.saveCount);
    }

    @Test
    void keepsTheOriginalGraceDeadlineWhenTheInstructorDropsRepeatedly() {
        heartbeat(INSTRUCTOR_USER, ConnectionState.DISCONNECTED); // T0 → 마감 T0+5
        clock.setInstant(T0.plus(Duration.ofMinutes(2)));
        heartbeat(INSTRUCTOR_USER, ConnectionState.DISCONNECTED); // 유예 진행 중이라 갱신 안 됨

        assertEquals(Optional.of(T0.plus(Duration.ofMinutes(5))), presencePort.instructorGraceDeadline(SESSION_ID));
    }

    @Test
    void stillEndsTheSessionWhenTheInstructorReconnectsAfterTheGraceExpired() {
        heartbeat(INSTRUCTOR_USER, ConnectionState.DISCONNECTED);
        clock.setInstant(T0.plus(Duration.ofMinutes(6)));

        // 유예가 이미 지난 뒤 강사가 CONNECTED로 복귀해도 종료를 취소하지 못해야 한다.
        PresenceResult result = heartbeat(INSTRUCTOR_USER, ConnectionState.CONNECTED);

        assertEquals(ReconnectStatus.SESSION_ENDED, result.reconnectStatus());
        assertTrue(result.sessionEnded());
        assertTrue(sessionRepository.session.isEnded());
    }

    @Test
    void clearsPresenceAndReportsDisconnectedWhenAStudentLeaves() {
        heartbeat(STUDENT_USER, ConnectionState.CONNECTED);

        PresenceResult result = heartbeat(STUDENT_USER, ConnectionState.DISCONNECTED);

        assertEquals(ReconnectStatus.DISCONNECTED, result.reconnectStatus());
        assertFalse(result.sessionEnded());
        assertFalse(presencePort.presence.contains(SESSION_ID + ":" + STUDENT_PARTICIPANT));
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class InMemoryPresencePort implements SessionPresencePort {

        private final Set<String> presence = new HashSet<>();
        private final Map<Long, Instant> grace = new HashMap<>();

        @Override
        public void recordHeartbeat(long sessionId, long participantId, Duration ttl) {
            presence.add(sessionId + ":" + participantId);
        }

        @Override
        public void clearPresence(long sessionId, long participantId) {
            presence.remove(sessionId + ":" + participantId);
        }

        @Override
        public void startInstructorGrace(long sessionId, Instant deadline, Duration ttl) {
            // 실제 어댑터의 SETNX 시맨틱을 반영: 진행 중인 유예가 없을 때만 기록한다.
            grace.putIfAbsent(sessionId, deadline);
        }

        @Override
        public void clearInstructorGrace(long sessionId) {
            grace.remove(sessionId);
        }

        @Override
        public Optional<Instant> instructorGraceDeadline(long sessionId) {
            return Optional.ofNullable(grace.get(sessionId));
        }
    }

    private static final class FakeSessionRepository implements SessionRepository {

        private Session session;
        private int saveCount = 0;

        @Override
        public Session save(Session session) {
            this.session = session;
            saveCount++;
            return session;
        }

        @Override
        public Optional<Session> findById(Long id) {
            return Optional.ofNullable(session);
        }

        @Override
        public Optional<Session> findByInviteCode(String inviteCode) {
            return Optional.empty();
        }
    }

    private static final class FakeParticipantRepository implements SessionParticipantRepository {

        private final Map<Long, SessionParticipant> byUserId = new HashMap<>();

        @Override
        public Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId) {
            return Optional.ofNullable(byUserId.get(userId));
        }

        @Override
        public SessionParticipant save(SessionParticipant sessionParticipant) {
            return sessionParticipant;
        }
    }
}
