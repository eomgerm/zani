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

    private static Session sessionAt(long id, SessionStatus status, Instant startedAt, Instant noteDueAt) {
        return Session.reconstitute(
                id,
                1L,
                "제목",
                "INVITE" + id,
                false,
                startedAt,
                status,
                SessionAnalysisStatus.NOT_STARTED,
                null,
                noteDueAt,
                null);
    }

    private static Session liveSessionStartedAt(long id, Instant startedAt) {
        return sessionAt(id, SessionStatus.LIVE, startedAt, null);
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

    @Test
    void queriesSessionsStartedBeforeTheMaximumDurationCutoff() {
        service.expireDueSessions();

        assertEquals(NOW.minus(Session.ACTIVE_DURATION), sessionRepository.requestedCutoff);
    }

    /** 방치된 준비 상태 세션을 정리하지 않으면 강사의 활성 세션 잠금이 계속 잡혀 새 수업을 열 수 없다. */
    @Test
    void endsPreparingSessionsThatWereNeverStarted() {
        sessionRepository.abandoned = List.of(sessionAt(3L, SessionStatus.PREPARING, null, null));

        int ended = service.expireDueSessions();

        assertEquals(1, ended);
        assertEquals(List.of(3L), endSessionUseCase.endedSessionIds);
        assertEquals(List.of(SessionEndReason.ABANDONED_BEFORE_START), endSessionUseCase.reasons);
    }

    /** 메모 마감이 지난 세션은 이미 종료 절차를 마쳤으므로 종료 유스케이스를 다시 태우지 않고 최종 종료로만 넘긴다. */
    @Test
    void closesTheNoteWindowOfSessionsWhoseDeadlineHasPassed() {
        Session notePending = sessionAt(
                4L, SessionStatus.NOTE_PENDING, NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofMinutes(1)));
        sessionRepository.notePendingDue = List.of(notePending);

        service.expireDueSessions();

        assertEquals(SessionStatus.ENDED, notePending.status());
        assertEquals(List.of(4L), sessionRepository.savedIds);
        assertTrue(endSessionUseCase.endedSessionIds.isEmpty());
    }

    @Test
    void leavesTheNoteWindowAloneBeforeItsDeadline() {
        Session notePending = sessionAt(
                5L, SessionStatus.NOTE_PENDING, NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofMinutes(10)));
        sessionRepository.notePendingDue = List.of(notePending);

        service.expireDueSessions();

        assertEquals(SessionStatus.NOTE_PENDING, notePending.status());
        assertTrue(sessionRepository.savedIds.isEmpty());
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
            return new EndSessionResult(command.sessionId(), SessionStatus.NOTE_PENDING, true);
        }
    }

    private static final class FakeSessionRepository implements SessionRepository {

        private List<Session> due = List.of();
        private List<Session> abandoned = List.of();
        private List<Session> notePendingDue = List.of();
        private final List<Long> savedIds = new ArrayList<>();
        private Instant requestedCutoff;

        @Override
        public Session save(Session session) {
            savedIds.add(session.id());
            return session;
        }

        @Override
        public List<Session> findLiveStartedBefore(Instant startedBefore, int limit) {
            requestedCutoff = startedBefore;
            return due;
        }

        @Override
        public List<Session> findPreparingCreatedBefore(Instant createdBefore, int limit) {
            return abandoned;
        }

        @Override
        public List<Session> findNotePendingDueBefore(Instant dueBefore, int limit) {
            return notePendingDue;
        }

        @Override
        public Optional<Session> findById(Long id) {
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
