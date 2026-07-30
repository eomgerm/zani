package com.a105.zani.session.application.resolveconnectedstudents;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.port.SessionPresencePort;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionAnalysisStatus;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 접속 중인 학생 조회. DB 명단과 presence 를 합치는 경계를 고정한다. */
class ResolveConnectedStudentsServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long INSTRUCTOR_ID = 9L;
    private static final Instant SESSION_STARTED_AT = Instant.parse("2026-07-29T09:00:00Z");
    private static final Instant CONNECTED_AT = Instant.parse("2026-07-29T09:05:00Z");

    private final StubSessionRepository sessions = new StubSessionRepository();
    private final StubParticipantRepository participants = new StubParticipantRepository();
    private final InMemoryPresencePort presencePort = new InMemoryPresencePort();

    private ResolveConnectedStudentsService service;

    @BeforeEach
    void setUp() {
        service = new ResolveConnectedStudentsService(sessions, participants, presencePort);
        sessions.status = SessionStatus.LIVE;
    }

    @Test
    void reportsOnlyStudentsThatCurrentlyHaveAPresenceKey() {
        participants.addStudent(1L);
        participants.addStudent(2L);
        presencePort.connect(1L, CONNECTED_AT);

        ResolveConnectedStudentsResult result = service.resolve(new ResolveConnectedStudentsQuery(SESSION_ID));

        // 접속이 끊긴 학생은 presence 키가 TTL 로 사라져 여기 오지 않는다.
        assertEquals(1, result.students().size());
        assertEquals(1L, result.students().get(0).participantId());
        assertEquals(CONNECTED_AT, result.students().get(0).connectedSince());
    }

    @Test
    void leavesTheInstructorOutEvenWhileConnected() {
        participants.addStudent(1L);
        presencePort.connect(1L, CONNECTED_AT);
        presencePort.connect(INSTRUCTOR_ID, CONNECTED_AT);

        ResolveConnectedStudentsResult result = service.resolve(new ResolveConnectedStudentsQuery(SESSION_ID));

        // 강사는 집계 대상이 아니고(§2), 강사 이탈은 presence 유예로 따로 다룬다.
        assertEquals(
                List.of(1L),
                result.students().stream().map(ConnectedStudent::participantId).toList());
    }

    @Test
    void asksPresenceOnlyAboutTheStudentsTheSessionHas() {
        participants.addStudent(1L);
        participants.addStudent(2L);

        service.resolve(new ResolveConnectedStudentsQuery(SESSION_ID));

        // 키 공간을 훑으면(SCAN) 다른 세션의 키까지 지나가고 학생 수와 무관하게 느려진다.
        assertEquals(List.of(List.of(1L, 2L)), presencePort.askedAbout);
    }

    @Test
    void reportsNobodyForAnEndedSessionInsteadOfFailing() {
        participants.addStudent(1L);
        presencePort.connect(1L, CONNECTED_AT);
        sessions.status = SessionStatus.ENDED;

        // 예외를 던지면 집계가 수업 종료 직후에 실패한다. "세는 학생이 없다"가 사실에 더 가깝다.
        assertTrue(service.resolve(new ResolveConnectedStudentsQuery(SESSION_ID))
                .students()
                .isEmpty());
    }

    @Test
    void refusesASessionThatDoesNotExist() {
        sessions.missing = true;

        assertThrows(
                SessionNotFoundException.class, () -> service.resolve(new ResolveConnectedStudentsQuery(SESSION_ID)));
    }

    private static final class StubSessionRepository implements SessionRepository {

        private SessionStatus status = SessionStatus.LIVE;
        private boolean missing;

        @Override
        public Optional<Session> findById(Long id) {
            if (missing) {
                return Optional.empty();
            }
            return Optional.of(Session.reconstitute(
                    SESSION_ID,
                    1L,
                    "테스트 수업",
                    "INVITE01",
                    false,
                    status,
                    SessionAnalysisStatus.NOT_STARTED,
                    SESSION_STARTED_AT,
                    null,
                    null));
        }

        @Override
        public Session save(Session session) {
            return session;
        }

        @Override
        public List<Session> findLiveStartedBefore(Instant startedBefore, int limit) {
            return List.of();
        }

        @Override
        public Optional<Session> findByInviteCode(String inviteCode) {
            return Optional.empty();
        }

        @Override
        public Optional<Session> findByInviteCodeForUpdate(String inviteCode) {
            return Optional.empty();
        }
    }

    private static final class StubParticipantRepository implements SessionParticipantRepository {

        private final List<SessionParticipant> participants = new ArrayList<>();

        private StubParticipantRepository() {
            participants.add(SessionParticipant.reconstitute(
                    INSTRUCTOR_ID, SESSION_ID, 900L, SessionParticipantRole.INSTRUCTOR, SESSION_STARTED_AT, null));
        }

        private void addStudent(long participantId) {
            participants.add(SessionParticipant.reconstitute(
                    participantId,
                    SESSION_ID,
                    participantId + 1_000L,
                    SessionParticipantRole.STUDENT,
                    SESSION_STARTED_AT,
                    null));
        }

        @Override
        public List<SessionParticipant> findBySessionId(Long sessionId) {
            return List.copyOf(participants);
        }

        @Override
        public Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId) {
            return Optional.empty();
        }

        @Override
        public Optional<SessionParticipant> findById(Long id) {
            return Optional.empty();
        }

        @Override
        public SessionParticipant save(SessionParticipant sessionParticipant) {
            return sessionParticipant;
        }
    }

    private static final class InMemoryPresencePort implements SessionPresencePort {

        private final Map<Long, Instant> connected = new HashMap<>();
        private final List<List<Long>> askedAbout = new ArrayList<>();

        private void connect(long participantId, Instant since) {
            connected.put(participantId, since);
        }

        @Override
        public Map<Long, Instant> connectedSince(long sessionId, Collection<Long> participantIds) {
            askedAbout.add(List.copyOf(participantIds));
            Map<Long, Instant> found = new HashMap<>();
            for (Long participantId : participantIds) {
                Instant since = connected.get(participantId);
                if (since != null) {
                    found.put(participantId, since);
                }
            }
            return found;
        }

        @Override
        public Instant recordHeartbeat(long sessionId, long participantId, Instant now, Duration ttl) {
            return connected.computeIfAbsent(participantId, key -> now);
        }

        @Override
        public void clearPresence(long sessionId, long participantId) {
            connected.remove(participantId);
        }

        @Override
        public void startInstructorGrace(long sessionId, Instant deadline, Duration ttl) {}

        @Override
        public void clearInstructorGrace(long sessionId) {}

        @Override
        public Optional<Instant> instructorGraceDeadline(long sessionId) {
            return Optional.empty();
        }
    }
}
