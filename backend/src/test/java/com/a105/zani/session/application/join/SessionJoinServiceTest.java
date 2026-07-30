package com.a105.zani.session.application.join;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.session.application.exception.SessionEndedException;
import com.a105.zani.session.application.exception.SessionFullException;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.exception.SessionNotStartedException;
import com.a105.zani.session.domain.exception.InvalidInviteCodeException;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionEndReason;
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
    private static final Instant NOW = Instant.parse("2026-07-30T09:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
    private final InMemorySessionParticipantRepository participantRepository =
            new InMemorySessionParticipantRepository();
    private final SessionJoinService service = new SessionJoinService(sessionRepository, participantRepository, clock);

    @Test
    void throwsWhenInviteCodeDoesNotMatchAnySession() {
        assertThrows(
                SessionNotFoundException.class, () -> service.join(new JoinSessionCommand("NOTFOUND", STUDENT_ID)));
    }

    @Test
    void firstJoinCreatesANewStudentParticipant() {
        sessionRepository.save(liveSession());

        JoinSessionResult result = service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID));

        assertEquals(SessionParticipantRole.STUDENT, result.role());
        assertEquals(1, participantRepository.saveCount());
    }

    @Test
    void secondJoinIsIdempotentAndDoesNotCreateADuplicateParticipant() {
        sessionRepository.save(liveSession());

        JoinSessionResult first = service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID));
        JoinSessionResult second = service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID));

        assertEquals(first.sessionId(), second.sessionId());
        assertEquals(1, participantRepository.rowCount());
        assertEquals(2, participantRepository.saveCount());
    }

    /** 사용자가 표시형이나 소문자로 옮겨 적어도 같은 수업에 들어가야 한다. 브라우저를 거치지 않는 호출도 있어 서버가 다시 맞춘다. */
    @Test
    void normalizesHyphensSpacesAndLowercaseBeforeLookup() {
        sessionRepository.save(liveSession());

        JoinSessionResult result = service.join(new JoinSessionCommand(" abcd-efgh ", STUDENT_ID));

        assertEquals(INVITE_CODE, result.inviteCode());
    }

    @Test
    void rejectsCodesThatAreNotEightAlphanumericCharactersAfterNormalization() {
        assertThrows(
                InvalidInviteCodeException.class, () -> service.join(new JoinSessionCommand("ABCD-EFG", STUDENT_ID)));
    }

    /** 시작 전과 종료 후를 다른 예외로 구분한다. 학생이 "기다려야 하는지 끝난 건지"를 알 수 있어야 한다. */
    @Test
    void rejectsJoiningASessionThatHasNotStartedYet() {
        sessionRepository.save(Session.prepare(10L, INSTRUCTOR_ID, "테스트 세션", INVITE_CODE));

        assertThrows(
                SessionNotStartedException.class, () -> service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID)));
    }

    @Test
    void rejectsJoiningASessionThatHasEnded() {
        Session session = liveSession();
        session.beginEnding(NOW, SessionEndReason.INSTRUCTOR_REQUEST);
        sessionRepository.save(session);

        assertThrows(SessionEndedException.class, () -> service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID)));
    }

    /** 정원 30명은 강사를 포함한 값이다. 강사 1명 + 학생 29명이 차면 그다음 학생은 거절된다. */
    @Test
    void rejectsTheJoinThatWouldExceedCapacity() {
        Session session = liveSession();
        sessionRepository.save(session);
        seedParticipant(session.id(), INSTRUCTOR_ID, SessionParticipantRole.INSTRUCTOR);
        for (int i = 0; i < Session.CAPACITY - 1; i++) {
            seedParticipant(session.id(), 100L + i, SessionParticipantRole.STUDENT);
        }

        assertThrows(SessionFullException.class, () -> service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID)));
    }

    /** 정원이 찬 뒤에도 이미 들어와 있던 학생은 새로고침으로 다시 들어올 수 있다. 자리를 새로 쓰지 않기 때문이다. */
    @Test
    void allowsAnExistingParticipantToRejoinEvenWhenFull() {
        Session session = liveSession();
        sessionRepository.save(session);
        seedParticipant(session.id(), INSTRUCTOR_ID, SessionParticipantRole.INSTRUCTOR);
        seedParticipant(session.id(), STUDENT_ID, SessionParticipantRole.STUDENT);
        for (int i = 0; i < Session.CAPACITY - 2; i++) {
            seedParticipant(session.id(), 100L + i, SessionParticipantRole.STUDENT);
        }

        JoinSessionResult result = service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID));

        assertEquals(SessionParticipantRole.STUDENT, result.role());
        assertEquals(Session.CAPACITY, participantRepository.rowCount());
    }

    private static Session liveSession() {
        Session session = Session.prepare(10L, INSTRUCTOR_ID, "테스트 세션", INVITE_CODE);
        session.start(NOW);
        return session;
    }

    private void seedParticipant(Long sessionId, long userId, SessionParticipantRole role) {
        participantRepository.save(SessionParticipant.join(TsidGenerator.generate(), sessionId, userId, role, NOW));
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

        /** 잠금은 실제 DB 만이 걸 수 있다. 단위 테스트에서는 조회 결과가 같은지만 보면 되므로 같은 맵을 읽는다. */
        @Override
        public Optional<Session> findByInviteCodeForUpdate(String inviteCode) {
            return findByInviteCode(inviteCode);
        }
    }

    private static class InMemorySessionParticipantRepository implements SessionParticipantRepository {

        private final Map<String, SessionParticipant> store = new HashMap<>();
        private int saveCount = 0;

        @Override
        public java.util.Optional<SessionParticipant> findById(Long id) {
            return store.values().stream().filter(p -> id.equals(p.id())).findFirst();
        }

        @Override
        public java.util.List<SessionParticipant> findBySessionId(Long sessionId) {
            return store.values().stream()
                    .filter(p -> sessionId.equals(p.sessionId()))
                    .toList();
        }

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
