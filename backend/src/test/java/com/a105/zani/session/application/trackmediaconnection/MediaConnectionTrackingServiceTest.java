package com.a105.zani.session.application.trackmediaconnection;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionEndReason;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LiveKit 연결 webhook 을 참가 관계에 반영하는 규칙. 위조된 identity 나 종료된 세션에 출석을 주면 안 된다. */
class MediaConnectionTrackingServiceTest {

    private static final long SESSION_ID = 10L;
    private static final long PARTICIPANT_ID = 456L;
    private static final Instant T0 = Instant.parse("2026-07-26T00:00:00Z");

    private final FakeSessionRepository sessions = new FakeSessionRepository();
    private final FakeParticipantRepository participants = new FakeParticipantRepository();
    private final MediaConnectionTrackingService service = new MediaConnectionTrackingService(sessions, participants);

    private SessionParticipant enrolledStudent() {
        SessionParticipant participant =
                SessionParticipant.enroll(PARTICIPANT_ID, SESSION_ID, 7L, SessionParticipantRole.STUDENT, T0);
        participants.save(participant);
        return participant;
    }

    private Session liveSession() {
        Session session = Session.prepare(SESSION_ID, 1L, "제목", "ABCDEFGH");
        session.markLive(T0);
        sessions.session = session;
        return session;
    }

    private static MediaConnectionCommand joinOf(String identity, Instant at) {
        return new MediaConnectionCommand(SESSION_ID, identity, at);
    }

    @Test
    void confirmsFirstJoinForAKnownParticipant() {
        liveSession();
        SessionParticipant participant = enrolledStudent();
        Instant joinedAt = T0.plusSeconds(30);

        assertTrue(service.confirmJoined(joinOf("p-" + PARTICIPANT_ID, joinedAt)));

        assertEquals(joinedAt, participant.firstJoinedAt());
        assertEquals(1, participants.saveCount);
    }

    @Test
    void reportsAReconnectAsNotBeingAFirstJoin() {
        liveSession();
        SessionParticipant participant = enrolledStudent();
        service.confirmJoined(joinOf("p-" + PARTICIPANT_ID, T0.plusSeconds(30)));

        assertFalse(service.confirmJoined(joinOf("p-" + PARTICIPANT_ID, T0.plusSeconds(600))));
        assertEquals(T0.plusSeconds(30), participant.firstJoinedAt());
    }

    /** Egress·Agent 같은 시스템 참가자는 p-{id} 형식이 아니라 자연히 걸러진다(가이드 §11). */
    @Test
    void ignoresIdentitiesThatAreNotOurParticipantFormat() {
        liveSession();
        enrolledStudent();

        assertFalse(service.confirmJoined(joinOf("EG_egress_worker", T0)));
        assertFalse(service.confirmJoined(joinOf("p-not-a-number", T0)));
        assertFalse(service.confirmJoined(joinOf(null, T0)));
        assertEquals(0, participants.saveCount);
    }

    /** 형식만 맞고 실체가 없는 identity 는 위조이거나 다른 세션의 토큰 재사용이다. */
    @Test
    void ignoresAParticipantThatBelongsToAnotherSession() {
        liveSession();
        participants.save(SessionParticipant.enroll(PARTICIPANT_ID, 999L, 7L, SessionParticipantRole.STUDENT, T0));

        assertFalse(service.confirmJoined(joinOf("p-" + PARTICIPANT_ID, T0)));
        assertEquals(0, participants.saveCount);
    }

    /** 종료 절차에 들어간 세션에 뒤늦게 붙는 건 기존 토큰 재사용이다. 출석을 주지 않는다(가이드 §12). */
    @Test
    void refusesAttendanceOnceTheSessionHasStartedEnding() {
        Session session = liveSession();
        SessionParticipant participant = enrolledStudent();
        session.beginEnding(SessionEndReason.INSTRUCTOR_REQUEST, T0);

        assertFalse(service.confirmJoined(joinOf("p-" + PARTICIPANT_ID, T0.plusSeconds(30))));

        assertNull(participant.firstJoinedAt());
        assertEquals(0, participants.saveCount);
    }

    @Test
    void recordsTheLeaveTimeWithoutRevokingAttendance() {
        liveSession();
        SessionParticipant participant = enrolledStudent();
        service.confirmJoined(joinOf("p-" + PARTICIPANT_ID, T0.plusSeconds(30)));

        service.recordLeft(joinOf("p-" + PARTICIPANT_ID, T0.plusSeconds(90)));

        assertEquals(T0.plusSeconds(90), participant.lastLeftAt());
        assertTrue(participant.hasJoinedMedia());
    }

    private static final class FakeSessionRepository implements SessionRepository {

        private Session session;

        @Override
        public Session save(Session session) {
            this.session = session;
            return session;
        }

        @Override
        public Optional<Session> findById(Long id) {
            return Optional.ofNullable(session);
        }

        @Override
        public List<Session> findLiveStartedBefore(Instant startedBefore, int limit) {
            return List.of();
        }

        @Override
        public List<Session> findPreparingCreatedBefore(Instant createdBefore, int limit) {
            return List.of();
        }

        @Override
        public List<Session> findNotePendingDueBefore(Instant dueBefore, int limit) {
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

    private static final class FakeParticipantRepository implements SessionParticipantRepository {

        private final Map<Long, SessionParticipant> byId = new HashMap<>();
        private int saveCount = 0;

        @Override
        public Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId) {
            return byId.values().stream()
                    .filter(p -> p.sessionId().equals(sessionId) && p.userId().equals(userId))
                    .findFirst();
        }

        @Override
        public Optional<SessionParticipant> findById(Long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<SessionParticipant> findBySessionId(Long sessionId) {
            return byId.values().stream()
                    .filter(p -> p.sessionId().equals(sessionId))
                    .toList();
        }

        @Override
        public long countBySessionId(Long sessionId) {
            return findBySessionId(sessionId).size();
        }

        @Override
        public SessionParticipant save(SessionParticipant participant) {
            if (byId.containsKey(participant.id())) {
                saveCount++;
            }
            byId.put(participant.id(), participant);
            return participant;
        }
    }
}
