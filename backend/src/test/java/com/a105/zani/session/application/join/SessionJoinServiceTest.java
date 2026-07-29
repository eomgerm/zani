package com.a105.zani.session.application.join;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.application.exception.SessionCapacityReachedException;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.exception.SessionNotJoinableException;
import com.a105.zani.session.domain.exception.InvalidInviteCodeException;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionJoinServiceTest {

    private static final long SESSION_ID = 10L;
    private static final long INSTRUCTOR_ID = 1L;
    private static final long STUDENT_ID = 2L;
    private static final String INVITE_CODE = "ABCDEFGH";
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");

    private final InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
    private final InMemorySessionParticipantRepository participantRepository =
            new InMemorySessionParticipantRepository();
    private final SessionJoinService service =
            new SessionJoinService(sessionRepository, participantRepository, Clock.fixed(NOW, ZoneOffset.UTC));

    private Session liveSession() {
        Session session = Session.prepare(SESSION_ID, INSTRUCTOR_ID, "테스트 세션", INVITE_CODE);
        session.markLive(NOW);
        return sessionRepository.save(session);
    }

    @Test
    void throwsWhenInviteCodeDoesNotMatchAnySession() {
        assertThrows(
                SessionNotFoundException.class, () -> service.join(new JoinSessionCommand("NOTFOUND", STUDENT_ID)));
    }

    @Test
    void firstJoinCreatesANewStudentParticipant() {
        liveSession();

        JoinSessionResult result = service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID));

        assertEquals(SessionParticipantRole.STUDENT, result.role());
        assertEquals(1, participantRepository.rowCount());
    }

    /** 출석은 이 API 가 아니라 LiveKit 연결 webhook 이 확정한다. 여기서 시각을 채우면 초대 코드만 누르고 접속하지 않은 학생이 집계 분모에 들어간다. */
    @Test
    void doesNotConfirmAttendanceBecauseTheStudentHasNotConnectedToLiveKitYet() {
        liveSession();

        service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID));

        SessionParticipant joined = participantRepository
                .findBySessionIdAndUserId(SESSION_ID, STUDENT_ID)
                .orElseThrow();
        assertNull(joined.firstJoinedAt());
        assertEquals(NOW, joined.lastAccessedAt());
    }

    @Test
    void secondJoinIsIdempotentAndDoesNotCreateADuplicateParticipant() {
        liveSession();

        JoinSessionResult first = service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID));
        JoinSessionResult second = service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID));

        assertEquals(first.sessionId(), second.sessionId());
        assertEquals(1, participantRepository.rowCount());
        assertEquals(2, participantRepository.saveCount());
    }

    /** UI 는 A7KM-2PQR 처럼 보여 주고 사용자는 소문자로 옮겨 적는다. 둘 다 같은 수업으로 들어가야 한다. */
    @Test
    void acceptsTheDisplayFormAndLowercaseInput() {
        liveSession();

        assertEquals(
                SESSION_ID,
                service.join(new JoinSessionCommand("abcd-efgh", STUDENT_ID)).sessionId());
        assertEquals(
                SESSION_ID,
                service.join(new JoinSessionCommand("AbCdEfGh", STUDENT_ID)).sessionId());
        assertEquals(1, participantRepository.rowCount());
    }

    @Test
    void rejectsACodeThatCannotBeNormalisedToEightCharacters() {
        assertThrows(
                InvalidInviteCodeException.class, () -> service.join(new JoinSessionCommand("ABC-123", STUDENT_ID)));
    }

    @Test
    void rejectsASessionThatHasNotStartedYet() {
        sessionRepository.save(Session.prepare(SESSION_ID, INSTRUCTOR_ID, "테스트 세션", INVITE_CODE));

        assertThrows(
                SessionNotJoinableException.class, () -> service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID)));
    }

    @Test
    void rejectsASessionThatHasAlreadyEnded() {
        Session session = liveSession();
        session.beginEnding(com.a105.zani.session.domain.model.SessionEndReason.INSTRUCTOR_REQUEST, NOW);

        assertThrows(
                SessionNotJoinableException.class, () -> service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID)));
    }

    /** 정원 30 은 강사를 포함한다. 강사 1 + 학생 29 가 찼으면 31 번째는 거절한다. */
    @Test
    void rejectsTheThirtyFirstMemberBecauseTheInstructorCountsTowardsTheCap() {
        liveSession();
        participantRepository.save(
                SessionParticipant.enroll(900L, SESSION_ID, INSTRUCTOR_ID, SessionParticipantRole.INSTRUCTOR, NOW));
        for (int i = 0; i < SessionJoinService.MAX_PARTICIPANTS - 1; i++) {
            participantRepository.save(
                    SessionParticipant.enroll(1000L + i, SESSION_ID, 1000L + i, SessionParticipantRole.STUDENT, NOW));
        }

        assertEquals(SessionJoinService.MAX_PARTICIPANTS, participantRepository.rowCount());
        assertThrows(
                SessionCapacityReachedException.class,
                () -> service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID)));
    }

    /** 이미 들어와 있는 학생의 새로고침은 정원을 다시 소비하지 않는다. */
    @Test
    void letsAnExistingMemberBackInEvenWhenTheSessionIsFull() {
        liveSession();
        participantRepository.save(
                SessionParticipant.enroll(900L, SESSION_ID, STUDENT_ID, SessionParticipantRole.STUDENT, NOW));
        for (int i = 0; i < SessionJoinService.MAX_PARTICIPANTS - 1; i++) {
            participantRepository.save(
                    SessionParticipant.enroll(1000L + i, SESSION_ID, 1000L + i, SessionParticipantRole.STUDENT, NOW));
        }

        assertEquals(
                SESSION_ID,
                service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID)).sessionId());
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
            return Optional.ofNullable(byInviteCode.get(inviteCode));
        }

        @Override
        public Optional<Session> findByInviteCodeForUpdate(String inviteCode) {
            return findByInviteCode(inviteCode);
        }
    }

    private static class InMemorySessionParticipantRepository implements SessionParticipantRepository {

        private final Map<String, SessionParticipant> store = new HashMap<>();
        private int saveCount = 0;

        @Override
        public Optional<SessionParticipant> findById(Long id) {
            return store.values().stream().filter(p -> id.equals(p.id())).findFirst();
        }

        @Override
        public List<SessionParticipant> findBySessionId(Long sessionId) {
            return store.values().stream()
                    .filter(p -> sessionId.equals(p.sessionId()))
                    .toList();
        }

        @Override
        public long countBySessionId(Long sessionId) {
            return findBySessionId(sessionId).size();
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
