package com.a105.zani.session.application.create;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.application.exception.DuplicateInviteCodeException;
import com.a105.zani.session.application.exception.InviteCodeGenerationFailedException;
import com.a105.zani.session.application.port.SessionActivationLockPort;
import com.a105.zani.session.domain.InviteCodeGenerator;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateSessionServiceTest {

    private static final long INSTRUCTOR_ID = 1L;

    @Test
    void retriesWithANewCodeWhenTheFirstCodeIsAlreadyTaken() {
        Set<String> takenCodes = new HashSet<>(List.of("AAAAAAAA"));
        StubInviteCodeGenerator codeGenerator = new StubInviteCodeGenerator("AAAAAAAA", "BBBBBBBB");
        RecordingSessionRepository repository = new RecordingSessionRepository(takenCodes);
        CreateSessionService service = new CreateSessionService(
                new NewSessionSaver(repository), new AlwaysAcquireLockPort(), codeGenerator, event -> {});

        CreateSessionResult result = service.create(new CreateSessionCommand(INSTRUCTOR_ID, "재시도 테스트"));

        assertEquals("BBBBBBBB", result.inviteCode());
        assertEquals(2, repository.attemptCount());
    }

    @Test
    void failsAfterExhaustingAllRetryAttempts() {
        StubInviteCodeGenerator codeGenerator =
                new StubInviteCodeGenerator("AAAAAAAA", "AAAAAAAA", "AAAAAAAA", "AAAAAAAA", "AAAAAAAA");
        RecordingSessionRepository repository = new RecordingSessionRepository(new HashSet<>(List.of("AAAAAAAA")));
        CreateSessionService service = new CreateSessionService(
                new NewSessionSaver(repository), new AlwaysAcquireLockPort(), codeGenerator, event -> {});

        assertThrows(
                InviteCodeGenerationFailedException.class,
                () -> service.create(new CreateSessionCommand(INSTRUCTOR_ID, "재시도 실패 테스트")));
        assertEquals(5, repository.attemptCount());
    }

    @Test
    void publishesSessionCreatedEventOnSuccess() {
        RecordingSessionRepository repository = new RecordingSessionRepository(new HashSet<>());
        List<Object> published = new ArrayList<>();
        CreateSessionService service = new CreateSessionService(
                new NewSessionSaver(repository),
                new AlwaysAcquireLockPort(),
                new StubInviteCodeGenerator("CCCCCCCC"),
                published::add);

        CreateSessionResult result = service.create(new CreateSessionCommand(INSTRUCTOR_ID, "이벤트 발행 테스트"));

        assertEquals(1, published.size());
        SessionCreatedEvent event = (SessionCreatedEvent) published.get(0);
        assertEquals(result.sessionId(), event.sessionId());
        assertEquals(INSTRUCTOR_ID, event.instructorId());
    }

    @Test
    void doesNotPublishSessionCreatedEventWhenCreationFails() {
        RecordingSessionRepository repository = new RecordingSessionRepository(new HashSet<>(List.of("AAAAAAAA")));
        List<Object> published = new ArrayList<>();
        CreateSessionService service = new CreateSessionService(
                new NewSessionSaver(repository),
                new AlwaysAcquireLockPort(),
                new StubInviteCodeGenerator("AAAAAAAA", "AAAAAAAA", "AAAAAAAA", "AAAAAAAA", "AAAAAAAA"),
                published::add);

        assertThrows(
                InviteCodeGenerationFailedException.class,
                () -> service.create(new CreateSessionCommand(INSTRUCTOR_ID, "이벤트 미발행 테스트")));
        assertTrue(published.isEmpty(), "세션 생성이 실패하면 이벤트를 발행하지 않아야 한다");
    }

    private static class StubInviteCodeGenerator extends InviteCodeGenerator {

        private final Deque<String> codes;

        private StubInviteCodeGenerator(String... codes) {
            this.codes = new ArrayDeque<>(List.of(codes));
        }

        @Override
        public String generate() {
            return codes.pollFirst();
        }
    }

    private static class RecordingSessionRepository implements SessionRepository {

        private final Set<String> takenCodes;
        private int attemptCount = 0;

        private RecordingSessionRepository(Set<String> takenCodes) {
            this.takenCodes = takenCodes;
        }

        @Override
        public Session save(Session session) {
            attemptCount++;
            if (takenCodes.contains(session.inviteCode())) {
                throw new DuplicateInviteCodeException(new IllegalStateException("duplicate"));
            }
            return session;
        }

        int attemptCount() {
            return attemptCount;
        }

        @Override
        public java.util.List<Session> findLiveStartedBefore(java.time.Instant startedBefore, int limit) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public java.util.Optional<Session> findById(Long id) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public java.util.Optional<Session> findByInviteCode(String inviteCode) {
            throw new UnsupportedOperationException("not needed for this test");
        }
    }

    private static class AlwaysAcquireLockPort implements SessionActivationLockPort {

        @Override
        public boolean tryAcquire(long instructorId, Duration ttl) {
            return true;
        }

        @Override
        public void release(long instructorId) {}
    }
}
