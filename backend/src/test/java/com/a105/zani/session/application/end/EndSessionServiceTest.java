package com.a105.zani.session.application.end;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionAnalysisStatus;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.repository.SessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndSessionServiceTest {

    private static final long SESSION_ID = 100L;
    private static final Instant STARTED_AT = Instant.parse("2026-07-26T00:00:00Z");

    private final FakeSessionRepository sessionRepository = new FakeSessionRepository();
    private final RecordingReleaseUseCase audioRelease = new RecordingReleaseUseCase();
    private final EndSessionService service = new EndSessionService(sessionRepository, audioRelease);

    /** 반납 호출 여부만 기록하는 페이크. 코칭 오디오 버퍼는 세션당 수십 MB라 종료 시 반드시 반납돼야 한다. */
    private static final class RecordingReleaseUseCase
            implements com.a105.zani.audioclip.application.releaseaudio.ReleaseInstructorAudioUseCase {

        private final java.util.List<Long> released = new java.util.ArrayList<>();

        @Override
        public void release(long sessionId) {
            released.add(sessionId);
        }
    }

    private static Session sessionWith(SessionStatus status) {
        return Session.reconstitute(
                SESSION_ID, 1L, "제목", "INVITE01", false, STARTED_AT, status, SessionAnalysisStatus.NOT_STARTED);
    }

    @Test
    void endsALiveSessionAndReportsTheTransition() {
        sessionRepository.session = sessionWith(SessionStatus.LIVE);

        EndSessionResult result = service.end(new EndSessionCommand(SESSION_ID, SessionEndReason.MAX_DURATION_REACHED));

        assertTrue(result.ended());
        assertEquals(SessionStatus.ENDED, result.status());
        assertTrue(sessionRepository.session.isEnded());
        assertEquals(1, sessionRepository.saveCount);
        // 코칭 오디오 버퍼(세션당 수십 MB)를 반납하지 않으면 수업이 끝나도 메모리가 남는다.
        assertEquals(java.util.List.of(SESSION_ID), audioRelease.released);
    }

    @Test
    void isIdempotentWhenTheSessionHasAlreadyEnded() {
        sessionRepository.session = sessionWith(SessionStatus.ENDED);

        EndSessionResult result = service.end(new EndSessionCommand(SESSION_ID, SessionEndReason.INSTRUCTOR_ABSENT));

        assertFalse(result.ended());
        assertEquals(SessionStatus.ENDED, result.status());
        assertEquals(0, sessionRepository.saveCount);
        // 이미 끝난 세션의 버퍼는 첫 종료에서 이미 반납됐다. 중복 호출하지 않는다.
        assertTrue(audioRelease.released.isEmpty());
    }

    @Test
    void throwsWhenTheSessionDoesNotExist() {
        sessionRepository.session = null;

        assertThrows(
                SessionNotFoundException.class,
                () -> service.end(new EndSessionCommand(SESSION_ID, SessionEndReason.INSTRUCTOR_ABSENT)));
    }

    private static final class FakeSessionRepository implements SessionRepository {

        private Session session;
        private int saveCount = 0;

        @Override
        public Session save(Session session) {
            this.session = session;
            saveCount++;
            return session;
        }

        @Override
        public List<Session> findLiveStartedBefore(Instant startedBefore, int limit) {
            return List.of();
        }

        @Override
        public Optional<Session> findById(Long id) {
            return Optional.ofNullable(session);
        }

        @Override
        public Optional<Session> findByInviteCode(String inviteCode) {
            return Optional.empty();
        }
    }
}
