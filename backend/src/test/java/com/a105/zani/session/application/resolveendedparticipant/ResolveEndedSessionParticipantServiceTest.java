package com.a105.zani.session.application.resolveendedparticipant;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.SessionNotEndedException;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionAnalysisStatus;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResolveEndedSessionParticipantServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long INSTRUCTOR_USER = 7L;
    private static final long INSTRUCTOR_PARTICIPANT = 1L;
    private static final long STRANGER_USER = 9L;
    private static final Instant T0 = Instant.parse("2026-07-28T09:00:00Z");
    private static final Instant ENDED_AT = Instant.parse("2026-07-28T10:30:00Z");

    private final FakeSessionRepository sessionRepository = new FakeSessionRepository();
    private final FakeParticipantRepository participantRepository = new FakeParticipantRepository();

    private ResolveEndedSessionParticipantService service;

    @BeforeEach
    void setUp() {
        service = new ResolveEndedSessionParticipantService(sessionRepository, participantRepository);
        sessionRepository.session = session(SessionStatus.ENDED);
        participantRepository.byUserId.put(
                INSTRUCTOR_USER,
                SessionParticipant.reconstitute(
                        INSTRUCTOR_PARTICIPANT,
                        SESSION_ID,
                        INSTRUCTOR_USER,
                        SessionParticipantRole.INSTRUCTOR,
                        T0,
                        T0));
    }

    @Test
    void returnsTheParticipantIdAndRoleForAMemberOfAnEndedSession() {
        ResolveEndedSessionParticipantResult result = service.resolve(query(INSTRUCTOR_USER));

        assertEquals(INSTRUCTOR_PARTICIPANT, result.participantId());
        assertEquals(SessionParticipantRole.INSTRUCTOR, result.role());
    }

    @Test
    void carriesTheSessionWindowSoCallersCanPlaceEventsInsideTheClass() {
        // 리포트는 수업 안의 상대 시각으로 그려진다. 두 시각이 없으면 부르는 쪽이 session 엔티티를 직접 읽게 된다.
        ResolveEndedSessionParticipantResult result = service.resolve(query(INSTRUCTOR_USER));

        assertEquals(T0, result.startedAt());
        assertEquals(ENDED_AT, result.endedAt());
    }

    @Test
    void leavesTheEndTimeNullForASessionThatEndedBeforeWeRecordedIt() {
        // ended_at 을 저장하기 시작한 것은 최근이라 그전에 끝난 세션은 추정할 수 없다. 감추지 않고 null 로 넘겨
        // 받는 쪽이 관측 시각으로 길이를 파생하도록 판단을 넘긴다.
        sessionRepository.session = session(SessionStatus.ENDED, null);

        assertNull(service.resolve(query(INSTRUCTOR_USER)).endedAt());
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

        assertThrows(SessionNotFoundException.class, () -> service.resolve(query(INSTRUCTOR_USER)));
    }

    @Test
    void rejectsASessionThatIsStillInProgress() {
        // 진행 중 세션을 거절한다 — 사후 경로는 수업이 끝난 뒤에만 열린다(진행 중 세션 쪽과 조건이 정반대다).
        sessionRepository.session = session(SessionStatus.LIVE);

        assertThrows(SessionNotEndedException.class, () -> service.resolve(query(INSTRUCTOR_USER)));
    }

    private ResolveEndedSessionParticipantQuery query(long userId) {
        return new ResolveEndedSessionParticipantQuery(SESSION_ID, userId);
    }

    private static Session session(SessionStatus status) {
        return session(status, status == SessionStatus.ENDED ? ENDED_AT : null);
    }

    private static Session session(SessionStatus status, Instant endedAt) {
        return Session.reconstitute(
                SESSION_ID,
                INSTRUCTOR_USER,
                "제목",
                "INVITE01",
                false,
                T0,
                endedAt,
                status,
                SessionAnalysisStatus.NOT_STARTED);
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
