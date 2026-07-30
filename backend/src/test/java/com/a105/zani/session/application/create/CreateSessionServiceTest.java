package com.a105.zani.session.application.create;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.application.exception.DuplicateInviteCodeException;
import com.a105.zani.session.application.exception.InviteCodeGenerationFailedException;
import com.a105.zani.session.application.port.SessionActivationLockPort;
import com.a105.zani.session.domain.InviteCodeGenerator;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CreateSessionServiceTest {

    private static final long INSTRUCTOR_ID = 1L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-30T09:00:00Z"), ZoneOffset.UTC);

    /**
     * 강사도 자기 수업의 참가자여야 한다.
     *
     * <p>미디어 토큰 발급과 presence 보고가 모두 참가자 행에서 참가자 ID 와 역할을 읽는다. 이 행이 없으면 강사는 자기가 만든 방에서 403 을 받아 카메라·마이크를 켤 수 없다.
     */
    @Test
    void enrollsTheInstructorAsAParticipantOfTheNewSession() {
        RecordingSessionRepository repository = new RecordingSessionRepository(new HashSet<>());
        InMemoryParticipantRepository participants = new InMemoryParticipantRepository();
        CreateSessionService service = new CreateSessionService(
                new NewSessionSaver(repository, participants, CLOCK),
                new AlwaysAcquireLockPort(),
                new StubInviteCodeGenerator("AAAAAAAA"));

        CreateSessionResult result = service.create(new CreateSessionCommand(INSTRUCTOR_ID, "강사 멤버십"));

        SessionParticipant instructor = participants
                .findBySessionIdAndUserId(result.sessionId(), INSTRUCTOR_ID)
                .orElseThrow();
        assertEquals(SessionParticipantRole.INSTRUCTOR, instructor.role());
        assertEquals(1, participants.findBySessionId(result.sessionId()).size());
    }

    @Test
    void retriesWithANewCodeWhenTheFirstCodeIsAlreadyTaken() {
        Set<String> takenCodes = new HashSet<>(List.of("AAAAAAAA"));
        StubInviteCodeGenerator codeGenerator = new StubInviteCodeGenerator("AAAAAAAA", "BBBBBBBB");
        RecordingSessionRepository repository = new RecordingSessionRepository(takenCodes);
        CreateSessionService service = new CreateSessionService(
                new NewSessionSaver(repository, new InMemoryParticipantRepository(), CLOCK),
                new AlwaysAcquireLockPort(),
                codeGenerator);

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
                new NewSessionSaver(repository, new InMemoryParticipantRepository(), CLOCK),
                new AlwaysAcquireLockPort(),
                codeGenerator);

        assertThrows(
                InviteCodeGenerationFailedException.class,
                () -> service.create(new CreateSessionCommand(INSTRUCTOR_ID, "재시도 실패 테스트")));
        assertEquals(5, repository.attemptCount());
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

        @Override
        public java.util.Optional<Session> findByInviteCodeForUpdate(String inviteCode) {
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

    /** 강사 멤버십이 세션과 함께 저장되는지 확인하기 위한 최소 구현. */
    private static class InMemoryParticipantRepository implements SessionParticipantRepository {

        private final List<SessionParticipant> saved = new ArrayList<>();

        @Override
        public Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId) {
            return saved.stream()
                    .filter(p -> sessionId.equals(p.sessionId()) && userId.equals(p.userId()))
                    .findFirst();
        }

        @Override
        public Optional<SessionParticipant> findById(Long id) {
            return saved.stream().filter(p -> id.equals(p.id())).findFirst();
        }

        @Override
        public List<SessionParticipant> findBySessionId(Long sessionId) {
            return saved.stream().filter(p -> sessionId.equals(p.sessionId())).toList();
        }

        @Override
        public SessionParticipant save(SessionParticipant sessionParticipant) {
            saved.add(sessionParticipant);
            return sessionParticipant;
        }
    }
}
