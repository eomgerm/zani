package com.a105.zani.session.application.issuemediatoken;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.a105.zani.member.application.get.GetMemberDisplayNameUseCase;
import com.a105.zani.session.application.exception.MediaTokenSessionNotFoundException;
import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.SessionAlreadyEndedException;
import com.a105.zani.session.application.port.IssuedMediaToken;
import com.a105.zani.session.application.port.LiveKitTokenPort;
import com.a105.zani.session.application.port.MediaTokenRequest;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionAnalysisStatus;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IssueMediaTokenServiceTest {

    private Session session;
    private SessionParticipant participant;
    private final AtomicReference<MediaTokenRequest> captured = new AtomicReference<>();

    private final SessionRepository sessionRepository = new SessionRepository() {
        @Override
        public Session save(Session s) {
            return s;
        }

        @Override
        public Optional<Session> findById(Long id) {
            return Optional.ofNullable(session);
        }

        @Override
        public java.util.List<Session> findLiveStartedBefore(java.time.Instant startedBefore, int limit) {
            return java.util.List.of();
        }

        @Override
        public java.util.List<Session> findPreparingCreatedBefore(java.time.Instant createdBefore, int limit) {
            return java.util.List.of();
        }

        @Override
        public Optional<Session> findByInviteCode(String inviteCode) {
            return Optional.empty();
        }

        @Override
        public Optional<Session> findByInviteCodeForUpdate(String inviteCode) {
            return Optional.empty();
        }
    };

    private final SessionParticipantRepository participantRepository = new SessionParticipantRepository() {
        @Override
        public Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId) {
            return Optional.ofNullable(participant);
        }

        @Override
        public Optional<SessionParticipant> findById(Long id) {
            return Optional.ofNullable(participant);
        }

        @Override
        public java.util.List<SessionParticipant> findBySessionId(Long sessionId) {
            return participant == null ? java.util.List.of() : java.util.List.of(participant);
        }

        @Override
        public SessionParticipant save(SessionParticipant p) {
            return p;
        }
    };

    private final GetMemberDisplayNameUseCase getMemberDisplayNameUseCase = displayNameOf("홍길동");

    private static GetMemberDisplayNameUseCase displayNameOf(String displayName) {
        return query -> Optional.ofNullable(displayName);
    }

    private final LiveKitTokenPort liveKitTokenPort = request -> {
        captured.set(request);
        return new IssuedMediaToken(
                "wss://livekit.example.com",
                "jwt-token",
                "zani-test-session-" + request.sessionId(),
                Instant.parse("2026-07-24T00:00:00Z"));
    };

    private final IssueMediaTokenService service = new IssueMediaTokenService(
            sessionRepository, participantRepository, getMemberDisplayNameUseCase, liveKitTokenPort);

    private static final Instant STARTED_AT = Instant.parse("2026-07-26T09:00:00Z");

    private Session sessionWith(SessionStatus status) {
        return Session.reconstitute(
                100L, 1L, "제목", "INVITE1", false, status, SessionAnalysisStatus.NOT_STARTED, STARTED_AT, null, null);
    }

    @Test
    void throwsNotFoundWhenTheSessionDoesNotExist() {
        participant = SessionParticipant.join(456L, 100L, 7L, SessionParticipantRole.STUDENT, Instant.now());
        session = null;
        assertThrows(
                MediaTokenSessionNotFoundException.class, () -> service.issue(new IssueMediaTokenCommand(100L, 7L)));
    }

    @Test
    void throwsConflictWhenTheSessionHasAlreadyEnded() {
        participant = SessionParticipant.join(456L, 100L, 7L, SessionParticipantRole.STUDENT, Instant.now());
        session = sessionWith(SessionStatus.ENDED);
        assertThrows(SessionAlreadyEndedException.class, () -> service.issue(new IssueMediaTokenCommand(100L, 7L)));
    }

    @Test
    void throwsForbiddenWhenTheUserIsNotASessionMember() {
        session = sessionWith(SessionStatus.LIVE);
        participant = null;
        assertThrows(NotSessionMemberException.class, () -> service.issue(new IssueMediaTokenCommand(100L, 7L)));
    }

    @Test
    void issuesATokenCarryingTheServerDecidedIdentityAndRoomName() {
        session = sessionWith(SessionStatus.LIVE);
        participant = SessionParticipant.join(456L, 100L, 7L, SessionParticipantRole.STUDENT, Instant.now());

        IssueMediaTokenResult result = service.issue(new IssueMediaTokenCommand(100L, 7L));

        assertEquals("p-456", result.participantIdentity());
        assertEquals("zani-test-session-100", result.roomName());
        assertEquals("wss://livekit.example.com", result.liveKitUrl());
        assertEquals("jwt-token", result.accessToken());
        // 강의실이 종료 임박 안내를 띄우려면 자동 종료 예정 시각(시작 + 3시간)이 함께 와야 한다.
        assertEquals(STARTED_AT.plus(Session.ACTIVE_DURATION), result.sessionExpiresAt());
        // 포트에 전달된 서버 결정 값 검증
        assertEquals("p-456", captured.get().identity());
        assertEquals("홍길동", captured.get().displayName());
        assertEquals(SessionParticipantRole.STUDENT, captured.get().role());
        assertEquals(100L, captured.get().sessionId());
    }

    @Test
    void fallsBackToTheDefaultDisplayNameWhenTheMemberHasNone() {
        session = sessionWith(SessionStatus.LIVE);
        participant = SessionParticipant.join(456L, 100L, 7L, SessionParticipantRole.STUDENT, Instant.now());
        IssueMediaTokenService serviceWithoutName = new IssueMediaTokenService(
                sessionRepository, participantRepository, displayNameOf(null), liveKitTokenPort);

        serviceWithoutName.issue(new IssueMediaTokenCommand(100L, 7L));

        assertEquals("참가자", captured.get().displayName());
    }
}
