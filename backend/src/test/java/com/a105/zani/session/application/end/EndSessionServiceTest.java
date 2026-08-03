package com.a105.zani.session.application.end;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.port.SessionActivationLockPort;
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
    /** sessionWith 가 만드는 세션의 강사. 잠금은 이 강사 기준으로 반납돼야 한다. */
    private static final long INSTRUCTOR_ID = 1L;

    /** 종료 시각으로 찍힐 값. 서비스가 주입받은 시계를 쓰는지 보려고 실제 현재 시각과 다른 값을 둔다. */
    private static final Instant NOW = Instant.parse("2026-07-26T01:23:45Z");

    private final FakeSessionRepository sessionRepository = new FakeSessionRepository();
    private final RecordingReleaseUseCase audioRelease = new RecordingReleaseUseCase();
    private final RecordingActivationLockPort activationLock = new RecordingActivationLockPort();
    private final FakeStopSessionRecording stopRecording = new FakeStopSessionRecording();
    private final FakeMediaRoomControl mediaRoomControl = new FakeMediaRoomControl();
    private final EndSessionService service = new EndSessionService(
            sessionRepository,
            audioRelease,
            activationLock,
            stopRecording,
            mediaRoomControl,
            Clock.fixed(NOW, ZoneOffset.UTC));

    /**
     * 수업이 끝나면 미디어 쪽도 정리돼야 한다.
     *
     * <p>room 을 닫지 않으면 이미 발급된 토큰의 TTL(10분) 동안 종료된 수업에 다시 들어갈 수 있고, Egress 를 멈추지 않으면 아무도 없는 방에서 녹화가 계속 돈다.
     */
    @Test
    void stopsTheRecordingAndClosesTheMediaRoom() {
        sessionRepository.session = sessionWith(SessionStatus.LIVE);

        service.end(new EndSessionCommand(SESSION_ID, SessionEndReason.INSTRUCTOR_REQUEST));

        assertEquals(List.of(SESSION_ID), stopRecording.stopped);
        assertEquals(List.of(SESSION_ID), mediaRoomControl.closed);
    }

    /**
     * 녹화를 먼저 멈추고 room 을 닫는지.
     *
     * <p>순서가 뒤집히면 아직 도는 Egress 가 입력을 잃은 채 끝나 녹화 파일이 온전히 닫히지 않는다.
     */
    @Test
    void stopsTheRecordingBeforeClosingTheRoom() {
        sessionRepository.session = sessionWith(SessionStatus.LIVE);

        service.end(new EndSessionCommand(SESSION_ID, SessionEndReason.INSTRUCTOR_REQUEST));

        assertEquals(List.of("stopRecording", "closeRoom"), callOrder);
    }

    /** 미디어 정리가 실패해도 종료는 남아야 한다 — 되돌리면 수업이 계속 살아 있는 것으로 남는다. */
    @Test
    void keepsTheSessionEndedEvenWhenTheMediaCleanupFails() {
        sessionRepository.session = sessionWith(SessionStatus.LIVE);
        stopRecording.failure = new IllegalStateException("LiveKit down");
        mediaRoomControl.failure = new IllegalStateException("LiveKit down");

        EndSessionResult result = service.end(new EndSessionCommand(SESSION_ID, SessionEndReason.INSTRUCTOR_REQUEST));

        assertTrue(result.ended());
        assertTrue(sessionRepository.session.isEnded());
    }

    /** 이미 끝난 세션은 미디어 정리도 다시 하지 않는다(첫 종료에서 이미 했다). */
    @Test
    void doesNotCleanUpMediaAgainForAnAlreadyEndedSession() {
        sessionRepository.session = sessionWith(SessionStatus.ENDED);

        service.end(new EndSessionCommand(SESSION_ID, SessionEndReason.INSTRUCTOR_ABSENT));

        assertTrue(stopRecording.stopped.isEmpty());
        assertTrue(mediaRoomControl.closed.isEmpty());
    }

    /** 두 정리 호출의 순서를 담는다. 순서가 곧 계약이라 호출 여부만으로는 부족하다. */
    private final List<String> callOrder = new java.util.ArrayList<>();

    private final class FakeStopSessionRecording
            implements com.a105.zani.recording.application.stoprecording.StopSessionRecordingUseCase {

        private final List<Long> stopped = new java.util.ArrayList<>();
        private RuntimeException failure;

        @Override
        public int stopRecording(Long sessionId) {
            callOrder.add("stopRecording");
            if (failure != null) {
                throw failure;
            }
            stopped.add(sessionId);
            return 1;
        }
    }

    private final class FakeMediaRoomControl implements com.a105.zani.session.application.port.MediaRoomControlPort {

        private final List<Long> closed = new java.util.ArrayList<>();
        private RuntimeException failure;

        @Override
        public boolean closeRoom(long sessionId) {
            callOrder.add("closeRoom");
            if (failure != null) {
                throw failure;
            }
            closed.add(sessionId);
            return true;
        }
    }

    /**
     * 수업을 끝낸 강사는 곧바로 다음 수업을 열 수 있어야 한다.
     *
     * <p>활성 잠금은 3시간 TTL 이라, 종료가 반납하지 않으면 그 시간 동안 "이미 진행 중인 수업이 있어요" 로 막힌다.
     */
    @Test
    void releasesTheActivationLockSoTheInstructorCanOpenTheNextSession() {
        sessionRepository.session = sessionWith(SessionStatus.LIVE);

        service.end(new EndSessionCommand(SESSION_ID, SessionEndReason.INSTRUCTOR_REQUEST));

        assertEquals(List.of(INSTRUCTOR_ID), activationLock.released());
    }

    /** 잠금 반납이 실패해도 종료를 되돌리지 않는다. 수업이 계속 살아 있는 것으로 남는 편이 더 나쁘다. */
    @Test
    void endsTheSessionEvenWhenTheLockStoreIsDown() {
        sessionRepository.session = sessionWith(SessionStatus.LIVE);
        activationLock.failOnRelease(new IllegalStateException("redis down"));

        EndSessionResult result = service.end(new EndSessionCommand(SESSION_ID, SessionEndReason.INSTRUCTOR_REQUEST));

        assertTrue(result.ended());
    }

    /** 잠금 반납 호출만 기록하는 페이크. */
    private static final class RecordingActivationLockPort implements SessionActivationLockPort {

        private final List<Long> released = new java.util.ArrayList<>();
        private RuntimeException failure;

        void failOnRelease(RuntimeException exception) {
            this.failure = exception;
        }

        List<Long> released() {
            return released;
        }

        @Override
        public boolean tryAcquire(long instructorId, Duration timeToLive) {
            return true;
        }

        @Override
        public void release(long instructorId) {
            if (failure != null) {
                throw failure;
            }
            released.add(instructorId);
        }
    }

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
        return sessionWith(status, null);
    }

    private static Session endedSessionAt(Instant endedAt) {
        return sessionWith(SessionStatus.ENDED, endedAt);
    }

    private static Session sessionWith(SessionStatus status, Instant endedAt) {
        return Session.reconstitute(
                SESSION_ID,
                INSTRUCTOR_ID,
                "제목",
                "INVITE01",
                false,
                STARTED_AT,
                endedAt,
                status,
                SessionAnalysisStatus.NOT_STARTED);
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

    /**
     * 종료 시각을 주입받은 시계에서 가져와 저장하는지.
     *
     * <p>이 값이 없으면 사후 리포트가 수업이 언제 끝났는지 알 수 없어 관측에서 길이를 추정해야 한다.
     */
    @Test
    void stampsTheEndTimeFromTheInjectedClock() {
        sessionRepository.session = sessionWith(SessionStatus.LIVE);

        service.end(new EndSessionCommand(SESSION_ID, SessionEndReason.INSTRUCTOR_REQUEST));

        assertEquals(NOW, sessionRepository.session.endedAt());
    }

    /** 이미 끝난 세션은 저장 자체를 하지 않으므로 처음 종료 시각이 덮이지 않는다. */
    @Test
    void doesNotRestampAnAlreadyEndedSession() {
        Instant firstEnd = STARTED_AT.plusSeconds(1800);
        sessionRepository.session = endedSessionAt(firstEnd);

        service.end(new EndSessionCommand(SESSION_ID, SessionEndReason.INSTRUCTOR_ABSENT));

        assertEquals(firstEnd, sessionRepository.session.endedAt());
        assertEquals(0, sessionRepository.saveCount);
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
