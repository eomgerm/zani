package com.a105.zani.session.application.create;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.model.SessionStatusChange;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;
import com.a105.zani.session.domain.repository.SessionStatusChangeRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateSessionServiceTest {

    private static final long INSTRUCTOR_ID = 1L;
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");

    private static CreateSessionService serviceWith(
            RecordingSessionRepository repository,
            InMemoryParticipantRepository participants,
            RecordingStatusChangeRepository statusChanges,
            InviteCodeGenerator codeGenerator) {
        return new CreateSessionService(
                new NewSessionSaver(repository, participants, statusChanges),
                new AlwaysAcquireLockPort(),
                codeGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 세션은 준비 상태로 만들어진다. 강사가 미디어를 붙이고 시작해야 LIVE 가 되고, 그 전에는 자동 종료 시각도 없다. */
    @Test
    void createsThePreparingSessionWithoutAStartTime() {
        RecordingSessionRepository repository = new RecordingSessionRepository(new HashSet<>());
        CreateSessionService service = serviceWith(
                repository,
                new InMemoryParticipantRepository(),
                new RecordingStatusChangeRepository(),
                new StubInviteCodeGenerator("AAAAAAAA"));

        CreateSessionResult result = service.create(new CreateSessionCommand(INSTRUCTOR_ID, "준비 상태 테스트"));

        assertEquals(SessionStatus.PREPARING, result.status());
        assertNull(result.expiresAt());
        assertNull(repository.saved.startedAt());
    }

    /** 미디어 토큰 발급과 presence heartbeat 가 모두 참가 관계로 멤버십을 확인한다. 이 행이 없으면 강사가 방을 만들자마자 자기 수업에 들어가지 못한다. */
    @Test
    void enrolsTheInstructorSoTheyCanImmediatelyRequestAMediaToken() {
        RecordingSessionRepository repository = new RecordingSessionRepository(new HashSet<>());
        InMemoryParticipantRepository participants = new InMemoryParticipantRepository();
        CreateSessionService service = serviceWith(
                repository,
                participants,
                new RecordingStatusChangeRepository(),
                new StubInviteCodeGenerator("AAAAAAAA"));

        CreateSessionResult result = service.create(new CreateSessionCommand(INSTRUCTOR_ID, "강사 멤버십 테스트"));

        SessionParticipant instructor = participants
                .findBySessionIdAndUserId(result.sessionId(), INSTRUCTOR_ID)
                .orElseThrow();
        assertEquals(SessionParticipantRole.INSTRUCTOR, instructor.role());
        // 강사도 실제로 LiveKit 에 붙어야 출석이다. 생성 시점에는 아직 접속 전이다.
        assertNull(instructor.firstJoinedAt());
    }

    @Test
    void recordsTheCreationTransitionSoTheLifecycleStartsFromAKnownPoint() {
        RecordingStatusChangeRepository statusChanges = new RecordingStatusChangeRepository();
        CreateSessionService service = serviceWith(
                new RecordingSessionRepository(new HashSet<>()),
                new InMemoryParticipantRepository(),
                statusChanges,
                new StubInviteCodeGenerator("AAAAAAAA"));

        service.create(new CreateSessionCommand(INSTRUCTOR_ID, "이력 테스트"));

        assertEquals(1, statusChanges.appended.size());
        assertNull(statusChanges.appended.get(0).fromStatus());
        assertEquals(SessionStatus.PREPARING, statusChanges.appended.get(0).toStatus());
    }

    @Test
    void retriesWithANewCodeWhenTheFirstCodeIsAlreadyTaken() {
        Set<String> takenCodes = new HashSet<>(List.of("AAAAAAAA"));
        RecordingSessionRepository repository = new RecordingSessionRepository(takenCodes);
        CreateSessionService service = serviceWith(
                repository,
                new InMemoryParticipantRepository(),
                new RecordingStatusChangeRepository(),
                new StubInviteCodeGenerator("AAAAAAAA", "BBBBBBBB"));

        CreateSessionResult result = service.create(new CreateSessionCommand(INSTRUCTOR_ID, "재시도 테스트"));

        assertEquals("BBBBBBBB", result.inviteCode());
        assertEquals(2, repository.attemptCount());
    }

    /** 재시도해도 강사의 LiveKit identity(p-{participantId})가 흔들리면 안 되므로 참가자 ID 는 한 번만 만든다. */
    @Test
    void keepsTheInstructorParticipantIdStableAcrossInviteCodeRetries() {
        InMemoryParticipantRepository participants = new InMemoryParticipantRepository();
        CreateSessionService service = serviceWith(
                new RecordingSessionRepository(new HashSet<>(List.of("AAAAAAAA"))),
                participants,
                new RecordingStatusChangeRepository(),
                new StubInviteCodeGenerator("AAAAAAAA", "BBBBBBBB"));

        service.create(new CreateSessionCommand(INSTRUCTOR_ID, "identity 안정성 테스트"));

        assertEquals(1, participants.saved.size());
    }

    @Test
    void failsAfterExhaustingAllRetryAttempts() {
        RecordingSessionRepository repository = new RecordingSessionRepository(new HashSet<>(List.of("AAAAAAAA")));
        CreateSessionService service = serviceWith(
                repository,
                new InMemoryParticipantRepository(),
                new RecordingStatusChangeRepository(),
                new StubInviteCodeGenerator("AAAAAAAA", "AAAAAAAA", "AAAAAAAA", "AAAAAAAA", "AAAAAAAA"));

        assertThrows(
                InviteCodeGenerationFailedException.class,
                () -> service.create(new CreateSessionCommand(INSTRUCTOR_ID, "재시도 실패 테스트")));
        assertEquals(5, repository.attemptCount());
    }

    /** 생성 알파벳에서 서로 헷갈리는 글자를 빼야 학생이 코드를 옮겨 적다 틀리지 않는다. */
    @Test
    void generatesCodesWithoutAmbiguousCharacters() {
        InviteCodeGenerator generator = new InviteCodeGenerator();

        for (int i = 0; i < 200; i++) {
            String code = generator.generate();
            assertEquals(8, code.length());
            assertTrue(
                    code.chars().noneMatch(c -> "OIL01".indexOf(c) >= 0),
                    () -> "ambiguous character in generated code: " + code);
        }
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
        private Session saved;
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
            this.saved = session;
            return session;
        }

        int attemptCount() {
            return attemptCount;
        }

        @Override
        public List<Session> findLiveStartedBefore(Instant startedBefore, int limit) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public List<Session> findPreparingCreatedBefore(Instant createdBefore, int limit) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public List<Session> findNotePendingDueBefore(Instant dueBefore, int limit) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public Optional<Session> findById(Long id) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public Optional<Session> findByInviteCode(String inviteCode) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public Optional<Session> findByInviteCodeForUpdate(String inviteCode) {
            throw new UnsupportedOperationException("not needed for this test");
        }
    }

    private static class InMemoryParticipantRepository implements SessionParticipantRepository {

        private final Map<String, SessionParticipant> store = new HashMap<>();
        private final List<Long> saved = new ArrayList<>();

        @Override
        public Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId) {
            return Optional.ofNullable(store.get(sessionId + ":" + userId));
        }

        @Override
        public Optional<SessionParticipant> findById(Long id) {
            return store.values().stream().filter(p -> id.equals(p.id())).findFirst();
        }

        @Override
        public List<SessionParticipant> findBySessionId(Long sessionId) {
            return store.values().stream()
                    .filter(p -> sessionId.equals(p.sessionId()))
                    .toList();
        }

        @Override
        public long countBySessionId(Long sessionId) {
            return findBySessionId(sessionId).size();
        }

        @Override
        public SessionParticipant save(SessionParticipant sessionParticipant) {
            if (!saved.contains(sessionParticipant.id())) {
                saved.add(sessionParticipant.id());
            }
            store.put(sessionParticipant.sessionId() + ":" + sessionParticipant.userId(), sessionParticipant);
            return sessionParticipant;
        }
    }

    private static class RecordingStatusChangeRepository implements SessionStatusChangeRepository {

        private final List<SessionStatusChange> appended = new ArrayList<>();

        @Override
        public void append(SessionStatusChange statusChange) {
            appended.add(statusChange);
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
