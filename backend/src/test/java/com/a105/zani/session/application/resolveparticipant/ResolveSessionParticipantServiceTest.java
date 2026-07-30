package com.a105.zani.session.application.resolveparticipant;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.SessionAlreadyEndedException;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionAnalysisStatus;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResolveSessionParticipantServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long STUDENT_USER = 8L;
    private static final long STUDENT_PARTICIPANT = 2L;
    private static final long STRANGER_USER = 9L;
    private static final Instant T0 = Instant.parse("2026-07-28T09:00:00Z");

    private final FakeSessionRepository sessionRepository = new FakeSessionRepository();
    private final FakeParticipantRepository participantRepository = new FakeParticipantRepository();

    private ResolveSessionParticipantService service;

    @BeforeEach
    void setUp() {
        service = new ResolveSessionParticipantService(sessionRepository, participantRepository);
        sessionRepository.session = session(SessionStatus.LIVE);
        participantRepository.byUserId.put(
                STUDENT_USER,
                SessionParticipant.reconstitute(
                        STUDENT_PARTICIPANT,
                        SESSION_ID,
                        STUDENT_USER,
                        SessionParticipantRole.STUDENT,
                        T0,
                        T0,
                        null,
                        null));
    }

    @Test
    void returnsTheParticipantIdAndRoleForASessionMember() {
        ResolveSessionParticipantResult result = service.resolve(query(STUDENT_USER));

        assertEquals(STUDENT_PARTICIPANT, result.participantId());
        assertEquals(SessionParticipantRole.STUDENT, result.role());
        assertEquals(T0, result.sessionStartedAt());
        // 수업이 끝나면 의미가 없어지는 값의 보관 기간을 이 시각에 맞춘다.
        assertEquals(T0.plus(Session.ACTIVE_DURATION), result.sessionExpiresAt());
    }

    @Test
    void rejectsAUserWhoNeverJoinedTheSession() {
        assertThrows(NotSessionMemberException.class, () -> service.resolve(query(STRANGER_USER)));
    }

    @Test
    void checksMembershipBeforeSessionExistenceSoStrangersLearnNothing() {
        sessionRepository.session = null;

        // 세션이 없더라도 비멤버에게는 "멤버가 아님"만 알린다(세션 존재 여부를 노출하지 않는다).
        assertThrows(NotSessionMemberException.class, () -> service.resolve(query(STRANGER_USER)));
    }

    @Test
    void reportsAMissingSessionToAMemberWhoseSessionRowIsGone() {
        sessionRepository.session = null;

        assertThrows(SessionNotFoundException.class, () -> service.resolve(query(STUDENT_USER)));
    }

    @Test
    void rejectsAnAlreadyEndedSession() {
        sessionRepository.session = session(SessionStatus.ENDED);

        assertThrows(SessionAlreadyEndedException.class, () -> service.resolve(query(STUDENT_USER)));
    }

    private ResolveSessionParticipantQuery query(long userId) {
        return new ResolveSessionParticipantQuery(SESSION_ID, userId);
    }

    private static Session session(SessionStatus status) {
        return Session.reconstitute(
                SESSION_ID, 7L, "제목", "INVITE01", false, status, SessionAnalysisStatus.NOT_STARTED, T0, null, null);
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
        public Optional<Session> findByInviteCode(String inviteCode) {
            return Optional.empty();
        }

        @Override
        public Optional<Session> findByInviteCodeForUpdate(String inviteCode) {
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
        public Optional<SessionParticipant> findById(Long id) {
            return Optional.empty();
        }

        @Override
        public List<SessionParticipant> findBySessionId(Long sessionId) {
            return List.copyOf(byUserId.values());
        }

        @Override
        public SessionParticipant save(SessionParticipant sessionParticipant) {
            return sessionParticipant;
        }
    }
}
