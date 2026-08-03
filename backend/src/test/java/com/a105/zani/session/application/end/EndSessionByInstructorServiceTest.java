package com.a105.zani.session.application.end;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionAnalysisStatus;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.repository.SessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndSessionByInstructorServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long INSTRUCTOR_ID = 7L;
    private static final long STUDENT_ID = 8L;
    private static final Instant STARTED_AT = Instant.parse("2026-07-26T00:00:00Z");

    private final FakeSessionRepository sessionRepository = new FakeSessionRepository();
    private final RecordingEndSessionUseCase endSessionUseCase = new RecordingEndSessionUseCase();
    private final EndSessionByInstructorService service =
            new EndSessionByInstructorService(sessionRepository, endSessionUseCase);

    private static Session liveSession() {
        return Session.reconstitute(
                SESSION_ID,
                INSTRUCTOR_ID,
                "제목",
                "INVITE01",
                false,
                STARTED_AT,
                null,
                SessionStatus.LIVE,
                SessionAnalysisStatus.NOT_STARTED);
    }

    @Test
    void endsTheSessionThroughTheSharedUseCase() {
        sessionRepository.session = liveSession();

        EndSessionResult result = service.endByInstructor(new EndSessionByInstructorCommand(SESSION_ID, INSTRUCTOR_ID));

        assertTrue(result.ended());
        assertEquals(List.of(SESSION_ID), endSessionUseCase.endedSessionIds);
    }

    @Test
    void reportsTheRequestAsTheEndReason() {
        sessionRepository.session = liveSession();

        service.endByInstructor(new EndSessionByInstructorCommand(SESSION_ID, INSTRUCTOR_ID));

        assertEquals(List.of(SessionEndReason.INSTRUCTOR_REQUEST), endSessionUseCase.reasons);
    }

    @Test
    void rejectsAParticipantWhoDidNotOpenTheSession() {
        sessionRepository.session = liveSession();

        assertThrows(
                NotSessionInstructorException.class,
                () -> service.endByInstructor(new EndSessionByInstructorCommand(SESSION_ID, STUDENT_ID)));
        assertTrue(endSessionUseCase.endedSessionIds.isEmpty());
    }

    @Test
    void throwsWhenTheSessionDoesNotExist() {
        sessionRepository.session = null;

        assertThrows(
                SessionNotFoundException.class,
                () -> service.endByInstructor(new EndSessionByInstructorCommand(SESSION_ID, INSTRUCTOR_ID)));
    }

    private static final class RecordingEndSessionUseCase implements EndSessionUseCase {

        private final List<Long> endedSessionIds = new ArrayList<>();
        private final List<SessionEndReason> reasons = new ArrayList<>();

        @Override
        public EndSessionResult end(EndSessionCommand command) {
            endedSessionIds.add(command.sessionId());
            reasons.add(command.reason());
            return new EndSessionResult(command.sessionId(), SessionStatus.ENDED, true);
        }
    }

    private static final class FakeSessionRepository implements SessionRepository {

        private Session session;

        @Override
        public Session save(Session saved) {
            return saved;
        }

        @Override
        public Optional<Session> findById(Long id) {
            return Optional.ofNullable(session);
        }

        @Override
        public Optional<Session> findByInviteCode(String inviteCode) {
            return Optional.empty();
        }

        @Override
        public List<Session> findLiveStartedBefore(Instant startedBefore, int limit) {
            return List.of();
        }
    }
}
