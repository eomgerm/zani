package com.a105.zani.session.application.resolveendedsessionaccess;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResolveEndedSessionAccessServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long MISSING_SESSION_ID = 999L;
    private static final long INSTRUCTOR_USER = 7L;
    private static final long INSTRUCTOR_PARTICIPANT = 1L;
    private static final long STUDENT_USER = 8L;
    private static final long STUDENT_PARTICIPANT = 2L;
    private static final long STRANGER_USER = 9L;
    private static final Instant T0 = Instant.parse("2026-07-28T09:00:00Z");

    private final FakeSessionRepository sessionRepository = new FakeSessionRepository();
    private final FakeParticipantRepository participantRepository = new FakeParticipantRepository();

    private ResolveEndedSessionAccessService service;

    @BeforeEach
    void setUp() {
        service = new ResolveEndedSessionAccessService(sessionRepository, participantRepository);
        sessionRepository.session = session(SessionStatus.ENDED);
        participantRepository.put(INSTRUCTOR_USER, INSTRUCTOR_PARTICIPANT, SessionParticipantRole.INSTRUCTOR);
        participantRepository.put(STUDENT_USER, STUDENT_PARTICIPANT, SessionParticipantRole.STUDENT);
    }

    private static Session session(SessionStatus status) {
        return Session.reconstitute(
                SESSION_ID,
                INSTRUCTOR_USER,
                "리포트 테스트 수업",
                "ABCD1234",
                false,
                T0,
                status,
                SessionAnalysisStatus.NOT_STARTED);
    }

    private static ResolveEndedSessionAccessQuery query(long sessionId, long memberId) {
        return new ResolveEndedSessionAccessQuery(sessionId, memberId);
    }

    @Test
    @DisplayName("종료된 세션의 참가자면 역할과 시작·종료 시각을 돌려준다")
    void returns_role_and_times_for_a_participant() {
        ResolveEndedSessionAccessResult result = service.resolve(query(SESSION_ID, STUDENT_USER));

        assertThat(result.participantId()).isEqualTo(STUDENT_PARTICIPANT);
        assertThat(result.role()).isEqualTo(SessionParticipantRole.STUDENT);
        assertThat(result.startedAt()).isEqualTo(T0);
        // sessions.ended_at 은 애플리케이션이 쓰지 않아 늘 비어 있다. 조회 쪽이 그 사실을 보고 판단할 수
        // 있도록 감추지 않고 그대로 null 로 내보낸다.
        assertThat(result.endedAt()).isNull();
    }

    @Test
    @DisplayName("강사도 같은 경로로 접근을 판정받고 역할만 다르게 나온다")
    void the_instructor_gets_the_instructor_role() {
        ResolveEndedSessionAccessResult result = service.resolve(query(SESSION_ID, INSTRUCTOR_USER));

        assertThat(result.participantId()).isEqualTo(INSTRUCTOR_PARTICIPANT);
        assertThat(result.role()).isEqualTo(SessionParticipantRole.INSTRUCTOR);
    }

    @Test
    @DisplayName("아직 진행 중인 세션이면 409 다 — 실시간 경로를 쓰라는 뜻이다")
    void live_session_conflicts() {
        sessionRepository.session = session(SessionStatus.LIVE);

        assertThatThrownBy(() -> service.resolve(query(SESSION_ID, STUDENT_USER)))
                .isInstanceOf(SessionNotEndedException.class);
    }

    @Test
    @DisplayName("없는 세션은 404 다")
    void missing_session_is_not_found() {
        assertThatThrownBy(() -> service.resolve(query(MISSING_SESSION_ID, STUDENT_USER)))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    @DisplayName("이 세션의 참가자가 아니면 403 이다")
    void non_participant_is_forbidden() {
        assertThatThrownBy(() -> service.resolve(query(SESSION_ID, STRANGER_USER)))
                .isInstanceOf(NotSessionMemberException.class);
    }

    @Test
    @DisplayName("참가자가 아니면 세션이 진행 중인지도 알려주지 않는다")
    void a_stranger_learns_nothing_about_a_live_session() {
        sessionRepository.session = session(SessionStatus.LIVE);

        assertThatThrownBy(() -> service.resolve(query(SESSION_ID, STRANGER_USER)))
                .isInstanceOf(NotSessionMemberException.class);
    }

    private static final class FakeSessionRepository implements SessionRepository {

        private Session session;

        @Override
        public Session save(Session session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Session> findLiveStartedBefore(Instant startedBefore, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Session> findById(Long id) {
            return session != null && session.id().equals(id) ? Optional.of(session) : Optional.empty();
        }

        @Override
        public Optional<Session> findByInviteCode(String inviteCode) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeParticipantRepository implements SessionParticipantRepository {

        private final Map<Long, SessionParticipant> byUserId = new HashMap<>();

        void put(long userId, long participantId, SessionParticipantRole role) {
            byUserId.put(userId, SessionParticipant.reconstitute(participantId, SESSION_ID, userId, role, T0, T0));
        }

        @Override
        public Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId) {
            SessionParticipant participant = byUserId.get(userId);
            return participant != null && participant.sessionId().equals(sessionId)
                    ? Optional.of(participant)
                    : Optional.empty();
        }

        @Override
        public Optional<SessionParticipant> findById(Long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SessionParticipant> findBySessionId(Long sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionParticipant save(SessionParticipant sessionParticipant) {
            throw new UnsupportedOperationException();
        }
    }
}
