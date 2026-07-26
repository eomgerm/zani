package com.a105.zani.session.application.join;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionJoinServiceTest {

    private static final long INSTRUCTOR_ID = 1L;
    private static final long STUDENT_ID = 2L;
    private static final String INVITE_CODE = "ABCDEFGH";

    @Test
    void throwsWhenInviteCodeDoesNotMatchAnySession() {
        InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
        SessionJoinService service =
                new SessionJoinService(sessionRepository, new InMemorySessionParticipantRepository());

        assertThrows(
                SessionNotFoundException.class, () -> service.join(new JoinSessionCommand("NOTFOUND", STUDENT_ID)));
    }

    @Test
    void firstJoinCreatesANewStudentParticipant() {
        InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
        Session session = Session.start(10L, INSTRUCTOR_ID, "테스트 세션", INVITE_CODE, Instant.now());
        sessionRepository.save(session);
        InMemorySessionParticipantRepository participantRepository = new InMemorySessionParticipantRepository();
        SessionJoinService service = new SessionJoinService(sessionRepository, participantRepository);

        JoinSessionResult result = service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID));

        assertEquals(SessionParticipantRole.STUDENT, result.role());
        assertEquals(1, participantRepository.saveCount());
    }

    @Test
    void secondJoinIsIdempotentAndDoesNotCreateADuplicateParticipant() {
        InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
        Session session = Session.start(10L, INSTRUCTOR_ID, "테스트 세션", INVITE_CODE, Instant.now());
        sessionRepository.save(session);
        InMemorySessionParticipantRepository participantRepository = new InMemorySessionParticipantRepository();
        SessionJoinService service = new SessionJoinService(sessionRepository, participantRepository);

        JoinSessionResult first = service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID));
        JoinSessionResult second = service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID));

        assertEquals(first.sessionId(), second.sessionId());
        assertEquals(1, participantRepository.rowCount());
        assertEquals(2, participantRepository.saveCount());
    }

    private static class InMemorySessionRepository implements SessionRepository {

        private final Map<String, Session> byInviteCode = new HashMap<>();

        @Override
        public Session save(Session session) {
            byInviteCode.put(session.inviteCode(), session);
            return session;
        }

        @Override
        public Optional<Session> findById(Long id) {
            return Optional.empty();
        }

        @Override
        public java.util.List<Session> findLiveStartedBefore(java.time.Instant startedBefore, int limit) {
            return java.util.List.of();
        }

        @Override
        public Optional<Session> findByInviteCode(String inviteCode) {
            return Optional.ofNullable(byInviteCode.get(inviteCode));
        }
    }

    private static class InMemorySessionParticipantRepository implements SessionParticipantRepository {

        private final Map<String, SessionParticipant> store = new HashMap<>();
        private int saveCount = 0;

        @Override
        public Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId) {
            return Optional.ofNullable(store.get(key(sessionId, userId)));
        }

        @Override
        public SessionParticipant save(SessionParticipant sessionParticipant) {
            saveCount++;
            store.put(key(sessionParticipant.sessionId(), sessionParticipant.userId()), sessionParticipant);
            return sessionParticipant;
        }

        int saveCount() {
            return saveCount;
        }

        int rowCount() {
            return store.size();
        }

        private String key(Long sessionId, Long userId) {
            return sessionId + ":" + userId;
        }
    }
}
