package com.a105.zani.session.application.end;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionAnalysisStatus;
import com.a105.zani.session.domain.model.SessionEndReason;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.repository.SessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpireDueSessionsServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    private final FakeSessionRepository sessionRepository = new FakeSessionRepository();
    private final RecordingEndSessionUseCase endSessionUseCase = new RecordingEndSessionUseCase();
    private final ExpireDueSessionsService service =
            new ExpireDueSessionsService(sessionRepository, endSessionUseCase, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Session preparingSession(long id) {
        return Session.prepare(id, 1L, "제목", "INVITE" + id);
    }

    private static Session liveSessionStartedAt(long id, Instant startedAt) {
        return Session.reconstitute(
                id,
                1L,
                "제목",
                "INVITE" + id,
                false,
                SessionStatus.LIVE,
                SessionAnalysisStatus.NOT_STARTED,
                startedAt,
                null,
                null);
    }

    @Test
    void endsEverySessionThatReachedTheMaximumDuration() {
        sessionRepository.due = List.of(
                liveSessionStartedAt(1L, NOW.minus(Duration.ofHours(4))),
                liveSessionStartedAt(2L, NOW.minus(Duration.ofHours(3))));

        int ended = service.expireDueSessions();

        assertEquals(2, ended);
        assertEquals(List.of(1L, 2L), endSessionUseCase.endedSessionIds);
        assertTrue(
                endSessionUseCase.reasons.stream().allMatch(reason -> reason == SessionEndReason.MAX_DURATION_REACHED));
    }

    /** 준비 중인 세션은 시작 시각이 없어 최대 수업 시간 기준으로는 영원히 걸리지 않는다. 그대로 두면 생성만 하고 창을 닫은 수업이 홈 배너에 계속 남는다. */
    @Test
    void endsPreparingSessionsThatWereNeverStarted() {
        sessionRepository.abandoned = List.of(preparingSession(3L));

        int ended = service.expireDueSessions();

        assertEquals(1, ended);
        assertEquals(List.of(3L), endSessionUseCase.endedSessionIds);
        assertEquals(List.of(SessionEndReason.ABANDONED_BEFORE_START), endSessionUseCase.reasons);
    }

    /** 방치된 준비 세션은 생성 시각으로 찾는다. 시작 시각으로 찾으면 NULL 이라 아무 것도 걸리지 않는다. */
    @Test
    void queriesAbandonedSessionsByCreationTime() {
        service.expireDueSessions();

        assertEquals(NOW.minus(Session.ACTIVE_DURATION), sessionRepository.requestedCreatedCutoff);
    }

    @Test
    void queriesSessionsStartedBeforeTheMaximumDurationCutoff() {
        service.expireDueSessions();

        assertEquals(NOW.minus(Session.ACTIVE_DURATION), sessionRepository.requestedCutoff);
    }

    @Test
    void doesNotCountSessionsThatWereAlreadyEnded() {
        sessionRepository.due = List.of(liveSessionStartedAt(1L, NOW.minus(Duration.ofHours(4))));
        endSessionUseCase.alreadyEnded = true;

        assertEquals(0, service.expireDueSessions());
    }

    @Test
    void keepsGoingWhenOneSessionFailsToEnd() {
        sessionRepository.due = List.of(
                liveSessionStartedAt(1L, NOW.minus(Duration.ofHours(4))),
                liveSessionStartedAt(2L, NOW.minus(Duration.ofHours(4))));
        endSessionUseCase.failingSessionId = 1L;

        assertEquals(1, service.expireDueSessions());
        assertEquals(List.of(2L), endSessionUseCase.endedSessionIds);
    }

    private static final class RecordingEndSessionUseCase implements EndSessionUseCase {

        private final List<Long> endedSessionIds = new ArrayList<>();
        private final List<SessionEndReason> reasons = new ArrayList<>();
        private boolean alreadyEnded;
        private Long failingSessionId;

        @Override
        public EndSessionResult end(EndSessionCommand command) {
            if (failingSessionId != null && failingSessionId == command.sessionId()) {
                throw new IllegalStateException("db down");
            }
            if (alreadyEnded) {
                return new EndSessionResult(command.sessionId(), SessionStatus.ENDED, false);
            }
            endedSessionIds.add(command.sessionId());
            reasons.add(command.reason());
            return new EndSessionResult(command.sessionId(), SessionStatus.ENDED, true);
        }
    }

    private static final class FakeSessionRepository implements SessionRepository {

        private List<Session> due = List.of();
        private List<Session> abandoned = List.of();
        private Instant requestedCutoff;
        private Instant requestedCreatedCutoff;

        @Override
        public Session save(Session session) {
            return session;
        }

        @Override
        public List<Session> findLiveStartedBefore(Instant startedBefore, int limit) {
            requestedCutoff = startedBefore;
            return due;
        }

        @Override
        public List<Session> findPreparingCreatedBefore(Instant createdBefore, int limit) {
            requestedCreatedCutoff = createdBefore;
            return abandoned;
        }

        @Override
        public Optional<Session> findById(Long id) {
            return Optional.empty();
        }

        @Override
        public Optional<Session> findByIdForUpdate(Long id) {
            return Optional.empty();
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
}
