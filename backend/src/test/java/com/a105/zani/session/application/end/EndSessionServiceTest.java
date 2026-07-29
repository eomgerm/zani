package com.a105.zani.session.application.end;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.audioclip.application.releaseaudio.ReleaseInstructorAudioUseCase;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.port.SessionActivationLockPort;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionAnalysisStatus;
import com.a105.zani.session.domain.model.SessionEndReason;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.model.SessionStatusChange;
import com.a105.zani.session.domain.repository.SessionRepository;
import com.a105.zani.session.domain.repository.SessionStatusChangeRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndSessionServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long INSTRUCTOR_ID = 1L;
    private static final Instant STARTED_AT = Instant.parse("2026-07-26T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-07-26T01:00:00Z");

    private final FakeSessionRepository sessionRepository = new FakeSessionRepository();
    private final FakeStatusChangeRepository statusChanges = new FakeStatusChangeRepository();
    private final FakeActivationLockPort activationLock = new FakeActivationLockPort();
    private final RecordingReleaseUseCase audioRelease = new RecordingReleaseUseCase();
    private final EndSessionService service = new EndSessionService(
            sessionRepository, statusChanges, activationLock, audioRelease, Clock.fixed(NOW, ZoneOffset.UTC));

    /** 반납 호출 여부만 기록하는 페이크. 코칭 오디오 버퍼는 세션당 수십 MB라 종료 시 반드시 반납돼야 한다. */
    private static final class RecordingReleaseUseCase implements ReleaseInstructorAudioUseCase {

        private final List<Long> released = new ArrayList<>();

        @Override
        public void release(long sessionId) {
            released.add(sessionId);
        }
    }

    private static Session liveSession() {
        Session session = Session.prepare(SESSION_ID, INSTRUCTOR_ID, "제목", "INVITE01");
        session.markLive(STARTED_AT);
        return session;
    }

    private static Session sessionWith(SessionStatus status) {
        return Session.reconstitute(
                SESSION_ID,
                INSTRUCTOR_ID,
                "제목",
                "INVITE01",
                false,
                STARTED_AT,
                status,
                SessionAnalysisStatus.NOT_STARTED,
                null,
                null,
                null);
    }

    /** 종료는 ENDING 을 거쳐 NOTE_PENDING 에서 멈춘다. 최종 ENDED 는 메모 마감이 지나야 온다. */
    @Test
    void endsALiveSessionByMovingItThroughEndingIntoTheNoteWindow() {
        sessionRepository.session = liveSession();

        EndSessionResult result = service.end(new EndSessionCommand(SESSION_ID, SessionEndReason.MAX_DURATION_REACHED));

        assertTrue(result.ended());
        assertEquals(SessionStatus.NOTE_PENDING, result.status());
        assertEquals(SessionEndReason.MAX_DURATION_REACHED, sessionRepository.session.endReason());
        assertEquals(NOW, sessionRepository.session.endedAt());
        assertEquals(NOW.plus(Session.NOTE_WINDOW), sessionRepository.session.noteDueAt());
        assertEquals(1, sessionRepository.saveCount);
        // 코칭 오디오 버퍼(세션당 수십 MB)를 반납하지 않으면 수업이 끝나도 메모리가 남는다.
        assertEquals(List.of(SESSION_ID), audioRelease.released);
    }

    @Test
    void recordsBothTransitionsSoTheLifecycleCanBeTracedAfterwards() {
        sessionRepository.session = liveSession();

        service.end(new EndSessionCommand(SESSION_ID, SessionEndReason.INSTRUCTOR_REQUEST));

        assertEquals(
                List.of(SessionStatus.ENDING, SessionStatus.NOTE_PENDING),
                statusChanges.appended.stream()
                        .map(SessionStatusChange::toStatus)
                        .toList());
        assertEquals(SessionStatus.LIVE, statusChanges.appended.get(0).fromStatus());
    }

    /** 잠금은 3시간 TTL 이라, 반납하지 않으면 10분 만에 끝낸 강사가 2시간 50분 동안 새 수업을 열지 못한다. */
    @Test
    void releasesTheInstructorActivationLockSoANewSessionCanStartRightAway() {
        sessionRepository.session = liveSession();

        service.end(new EndSessionCommand(SESSION_ID, SessionEndReason.INSTRUCTOR_REQUEST));

        assertEquals(List.of(INSTRUCTOR_ID), activationLock.released);
    }

    /** 잠금 반납 실패는 이미 확정된 종료를 되돌릴 이유가 못 된다. 잠금은 TTL 로 스스로 사라진다. */
    @Test
    void stillEndsTheSessionWhenTheLockStoreIsUnavailable() {
        sessionRepository.session = liveSession();
        activationLock.failOnRelease = true;

        EndSessionResult result = service.end(new EndSessionCommand(SESSION_ID, SessionEndReason.INSTRUCTOR_REQUEST));

        assertTrue(result.ended());
        assertEquals(SessionStatus.NOTE_PENDING, result.status());
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
        assertTrue(statusChanges.appended.isEmpty());
    }

    /** 종료 절차 중(ENDING)에 도착한 중복 요청도 상태를 되돌리거나 이력을 늘리지 않는다. */
    @Test
    void isIdempotentWhileTheSessionIsStillEnding() {
        sessionRepository.session = sessionWith(SessionStatus.ENDING);

        EndSessionResult result = service.end(new EndSessionCommand(SESSION_ID, SessionEndReason.INSTRUCTOR_ABSENT));

        assertFalse(result.ended());
        assertEquals(SessionStatus.ENDING, result.status());
        assertEquals(0, sessionRepository.saveCount);
    }

    /** 시작하지 않고 방치된 준비 상태 세션도 같은 경로로 정리한다. */
    @Test
    void endsAPreparingSessionThatWasNeverStarted() {
        sessionRepository.session = sessionWith(SessionStatus.PREPARING);

        EndSessionResult result =
                service.end(new EndSessionCommand(SESSION_ID, SessionEndReason.ABANDONED_BEFORE_START));

        assertTrue(result.ended());
        assertEquals(SessionStatus.NOTE_PENDING, result.status());
        assertEquals(List.of(INSTRUCTOR_ID), activationLock.released);
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
        public List<Session> findPreparingCreatedBefore(Instant createdBefore, int limit) {
            return List.of();
        }

        @Override
        public List<Session> findNotePendingDueBefore(Instant dueBefore, int limit) {
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

        @Override
        public Optional<Session> findByInviteCodeForUpdate(String inviteCode) {
            return Optional.empty();
        }
    }

    private static final class FakeStatusChangeRepository implements SessionStatusChangeRepository {

        private final List<SessionStatusChange> appended = new ArrayList<>();

        @Override
        public void append(SessionStatusChange statusChange) {
            appended.add(statusChange);
        }
    }

    private static final class FakeActivationLockPort implements SessionActivationLockPort {

        private final List<Long> released = new ArrayList<>();
        private boolean failOnRelease = false;

        @Override
        public boolean tryAcquire(long instructorId, Duration ttl) {
            return true;
        }

        @Override
        public void release(long instructorId) {
            if (failOnRelease) {
                throw new IllegalStateException("lock store down");
            }
            released.add(instructorId);
        }
    }
}
