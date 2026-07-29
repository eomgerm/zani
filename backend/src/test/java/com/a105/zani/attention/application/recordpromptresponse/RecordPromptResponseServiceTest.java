package com.a105.zani.attention.application.recordpromptresponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.application.exception.AttentionStateUnavailableException;
import com.a105.zani.attention.application.exception.InvalidPromptTimelineException;
import com.a105.zani.attention.application.exception.NotPromptStudentException;
import com.a105.zani.attention.application.exception.StalePromptException;
import com.a105.zani.attention.application.port.AttentionSnapshot;
import com.a105.zani.attention.application.port.AttentionStatePort;
import com.a105.zani.attention.application.port.ObservationApplied;
import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.CheckPrompt;
import com.a105.zani.attention.domain.model.CheckPromptStatus;
import com.a105.zani.attention.domain.model.DetectionRunCounters;
import com.a105.zani.attention.domain.model.DetectionRunTransition;
import com.a105.zani.attention.domain.model.PromptAnswer;
import com.a105.zani.attention.domain.model.PromptKind;
import com.a105.zani.attention.domain.repository.CheckPromptRepository;
import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantQuery;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantResult;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordPromptResponseServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long STUDENT_USER = 8L;
    private static final long STUDENT_PARTICIPANT = 2L;
    private static final Instant SESSION_STARTED_AT = Instant.parse("2026-07-28T09:00:00Z");
    private static final Instant SHOWN_AT = SESSION_STARTED_AT.plusSeconds(60);
    private static final Instant RESPONDED_AT = SHOWN_AT.plusSeconds(8);
    private static final Instant SERVER_NOW = SESSION_STARTED_AT.plusSeconds(70);
    private static final Instant SESSION_EXPIRES_AT = SESSION_STARTED_AT.plus(Duration.ofHours(3));

    private final InMemoryAttentionStatePort statePort = new InMemoryAttentionStatePort();
    private final InMemoryCheckPromptRepository promptRepository = new InMemoryCheckPromptRepository();
    private final StubResolveSessionParticipant resolveParticipant = new StubResolveSessionParticipant();

    private RecordPromptResponseService service;

    @BeforeEach
    void setUp() {
        service = new RecordPromptResponseService(
                resolveParticipant, promptRepository, statePort, Clock.fixed(SERVER_NOW, ZoneOffset.UTC));
    }

    @Test
    void storesTheAnswerWithOffsetsMeasuredFromTheSessionStart() {
        RecordPromptResponseResult result = service.record(command(PromptAnswer.CONFUSED));

        assertTrue(result.accepted());
        assertFalse(result.duplicate());
        CheckPrompt saved = promptRepository.saved.get(0);
        assertEquals(CheckPromptStatus.RESPONDED, saved.status());
        assertEquals(PromptAnswer.CONFUSED, saved.answer());
        assertEquals(60_000L, saved.shownOffsetMs());
        assertEquals(68_000L, saved.respondedOffsetMs());
    }

    @Test
    void marksAnUnansweredPromptAsTimedOutWithoutARespondedTime() {
        service.record(command(PromptAnswer.NON_RESPONSE));

        CheckPrompt saved = promptRepository.saved.get(0);
        assertEquals(CheckPromptStatus.TIMEOUT, saved.status());
        assertNull(saved.respondedOffsetMs());
    }

    @Test
    void confirmsTheParticipationStateSoTheAggregationReadsItImmediately() {
        service.record(command(PromptAnswer.CONFUSED));

        AttentionSnapshot stored = statePort.currentState.get(STUDENT_PARTICIPANT);
        assertEquals(AttentionState.CONFUSED, stored.state());
        assertEquals(SERVER_NOW, stored.recordedAt());
        assertTrue(statePort.significant.contains(AttentionState.CONFUSED));
    }

    @Test
    void treatsAnUnderstoodAnswerAsGoodAndKeepsItOutOfTheTriggerWindow() {
        service.record(command(PromptAnswer.OK));

        assertEquals(
                AttentionState.GOOD,
                statePort.currentState.get(STUDENT_PARTICIPANT).state());
        assertTrue(statePort.significant.isEmpty());
    }

    @Test
    void treatsSilenceAsNonResponseInTheTriggerWindow() {
        service.record(command(PromptAnswer.NON_RESPONSE));

        assertTrue(statePort.significant.contains(AttentionState.NON_RESPONSE));
    }

    @Test
    void keepsTheFirstAnswerWhenTheSamePromptIsAnsweredTwice() {
        service.record(command(PromptAnswer.CONFUSED));

        RecordPromptResponseResult retry = service.record(command(PromptAnswer.OK));

        assertFalse(retry.accepted());
        assertTrue(retry.duplicate());
        assertEquals(1, promptRepository.saved.size());
        assertEquals(PromptAnswer.CONFUSED, promptRepository.saved.get(0).answer());
    }

    @Test
    void rejectsAPromptShownBeforeTheSessionEvenStarted() {
        RecordPromptResponseCommand beforeStart = new RecordPromptResponseCommand(
                SESSION_ID,
                STUDENT_USER,
                "prompt-1",
                PromptKind.UNDERSTANDING_CHECK,
                PromptAnswer.CONFUSED,
                SESSION_STARTED_AT.minusSeconds(1),
                RESPONDED_AT);

        // 눌러 담으면 서로 다른 프롬프트가 같은 오프셋을 갖게 되고, 두 번째 답이 중복으로 잡혀 사라진다.
        assertThrows(InvalidPromptTimelineException.class, () -> service.record(beforeStart));
        assertTrue(promptRepository.saved.isEmpty());
    }

    @Test
    void rejectsAnAnswerThatArrivedBeforeThePromptWasShown() {
        RecordPromptResponseCommand backwards = new RecordPromptResponseCommand(
                SESSION_ID,
                STUDENT_USER,
                "prompt-1",
                PromptKind.UNDERSTANDING_CHECK,
                PromptAnswer.CONFUSED,
                SHOWN_AT,
                SHOWN_AT.minusSeconds(1));

        assertThrows(InvalidPromptTimelineException.class, () -> service.record(backwards));
    }

    @Test
    void answersDuplicateRatherThanStaleForALateRetryOfARecordedAnswer() {
        service.record(command(PromptAnswer.CONFUSED));

        // 답은 이미 기록됐다. 재시도가 만료 창을 넘겨 도착했다고 409 를 주면 성공한 요청을 실패로 알리는 셈이다.
        RecordPromptResponseService lateService = new RecordPromptResponseService(
                resolveParticipant,
                promptRepository,
                statePort,
                Clock.fixed(SHOWN_AT.plusSeconds(600), ZoneOffset.UTC));

        RecordPromptResponseResult retry = lateService.record(command(PromptAnswer.CONFUSED));

        assertTrue(retry.duplicate());
        assertEquals(1, promptRepository.saved.size());
    }

    @Test
    void doesNotLeaveAMarkerBehindWhenAFirstAnswerArrivesTooLate() {
        RecordPromptResponseService lateService = new RecordPromptResponseService(
                resolveParticipant,
                promptRepository,
                statePort,
                Clock.fixed(SHOWN_AT.plusSeconds(600), ZoneOffset.UTC));

        assertThrows(StalePromptException.class, () -> lateService.record(command(PromptAnswer.CONFUSED)));

        // 만료로 거절하면서 표시만 남기면, 같은 프롬프트를 다시 볼 방법이 사라진다.
        assertTrue(statePort.markers.isEmpty());
    }

    @Test
    void refusesAnAnswerToAPromptThatIsTooOldToCount() {
        // 프롬프트는 정상 시점에 떴지만 답이 10분 뒤에야 도착했다. 집계 창(5분)을 벗어나 쓸 수 없다.
        RecordPromptResponseService lateService = new RecordPromptResponseService(
                resolveParticipant,
                promptRepository,
                statePort,
                Clock.fixed(SHOWN_AT.plusSeconds(600), ZoneOffset.UTC));

        assertThrows(StalePromptException.class, () -> lateService.record(command(PromptAnswer.CONFUSED)));
        assertTrue(promptRepository.saved.isEmpty());
    }

    @Test
    void letsTheAnswerBeSentAgainWhenSavingItFailed() {
        promptRepository.failSave = true;

        assertThrows(IllegalStateException.class, () -> service.record(command(PromptAnswer.CONFUSED)));

        // 표시만 남으면 재시도가 "이미 처리했다"는 거짓 성공을 받고 답이 영영 사라진다.
        promptRepository.failSave = false;
        assertTrue(service.record(command(PromptAnswer.CONFUSED)).accepted());
    }

    @Test
    void ignoresAnAnswerThatIsTooOldToSteerCoachingButStillKeepsIt() {
        // 관찰 창 안이라 저장은 받지만, 4분 전 답을 지금 상태로 찍으면 지나간 신호가 트리거를 계속 끌고 간다.
        RecordPromptResponseService lateService = new RecordPromptResponseService(
                resolveParticipant,
                promptRepository,
                statePort,
                Clock.fixed(SHOWN_AT.plusSeconds(240), ZoneOffset.UTC));

        assertTrue(lateService.record(command(PromptAnswer.CONFUSED)).accepted());

        assertEquals(1, promptRepository.saved.size());
        assertTrue(statePort.currentState.isEmpty());
        assertTrue(statePort.significant.isEmpty());
        // 4분 전 답이 지금 쌓이고 있는 연속 판정을 지우면, 이미 이탈한 학생의 프롬프트가 다시 미뤄진다.
        assertFalse(statePort.runsReset);
    }

    @Test
    void rejectsAnAnswerFromAClockThatRunsFarAhead() {
        RecordPromptResponseCommand fromTheFuture = new RecordPromptResponseCommand(
                SESSION_ID,
                STUDENT_USER,
                "prompt-1",
                PromptKind.UNDERSTANDING_CHECK,
                PromptAnswer.CONFUSED,
                SHOWN_AT,
                SERVER_NOW.plusSeconds(3_600));

        assertThrows(InvalidPromptTimelineException.class, () -> service.record(fromTheFuture));
        assertTrue(promptRepository.saved.isEmpty());
    }

    @Test
    void fallsBackToTheDatabaseWhenTheIdempotencyMarkerCannotBeWritten() {
        statePort.failMarker = true;

        service.record(command(PromptAnswer.CONFUSED));
        RecordPromptResponseResult retry = service.record(command(PromptAnswer.OK));

        // 저장소가 죽었다고 답을 거절하면 안 된다. 중복 판단만 DB 조회로 물러선다.
        assertTrue(retry.duplicate());
        assertEquals(1, promptRepository.saved.size());
    }

    @Test
    void clearsTheServerRunCountersWhenAPromptIsAnswered() {
        service.record(command(PromptAnswer.CONFUSED));

        // 프롬프트가 닫히면 브라우저 카운터가 0이 된다. 서버 사본만 남으면 다음 관측 한 건이 곧바로 분자에 다시 든다.
        assertTrue(statePort.runsReset);
    }

    @Test
    void keepsTheAnswerContractToTheFourConfirmedValues() {
        // 계약이 조용히 넓어지면 폐기한 카메라·자세 응답이 다시 들어와 분모를 흔들 수 있다.
        assertEquals(
                List.of(PromptAnswer.OK, PromptAnswer.CONFUSED, PromptAnswer.MISSED, PromptAnswer.NON_RESPONSE),
                List.of(PromptAnswer.values()));
        assertEquals(List.of(PromptKind.UNDERSTANDING_CHECK), List.of(PromptKind.values()));
    }

    @Test
    void neverTouchesTheGroupDenominatorWhateverTheStudentAnswers() {
        for (PromptAnswer answer : PromptAnswer.values()) {
            statePort.markers.clear();
            promptRepository.saved.clear();
            service.record(command(answer));
        }

        // 학생 답으로 분모에서 빼면 빠지는 쪽이 늘 유리해져 모두가 그 답을 고르고 분모가 계속 줄어든다(§5.2).
        // 제외는 서버가 검출기 이벤트로 판단한다(§7.1).
        assertTrue(statePort.excluded.isEmpty());
        assertNull(statePort.exclusionTtl);
    }

    @Test
    void rejectsAnAnswerSentByTheInstructor() {
        resolveParticipant.role = SessionParticipantRole.INSTRUCTOR;

        assertThrows(NotPromptStudentException.class, () -> service.record(command(PromptAnswer.CONFUSED)));
        assertTrue(promptRepository.saved.isEmpty());
    }

    @Test
    void rejectsAnAnswerFromSomeoneWhoIsNotASessionMember() {
        resolveParticipant.failure = new NotSessionMemberException();

        assertThrows(NotSessionMemberException.class, () -> service.record(command(PromptAnswer.CONFUSED)));
        assertTrue(promptRepository.saved.isEmpty());
    }

    @Test
    void keepsTheAnswerRecordedEvenWhenTheCoachingStoreIsDown() {
        statePort.failWrites = true;

        RecordPromptResponseResult result = service.record(command(PromptAnswer.CONFUSED));

        // 답은 수업 후 리포트의 근거다. 코칭 저장소가 죽었다고 기록까지 잃으면 안 된다.
        assertTrue(result.accepted());
        assertEquals(1, promptRepository.saved.size());
    }

    private RecordPromptResponseCommand command(PromptAnswer answer) {
        return new RecordPromptResponseCommand(
                SESSION_ID, STUDENT_USER, "prompt-1", PromptKind.UNDERSTANDING_CHECK, answer, SHOWN_AT, RESPONDED_AT);
    }

    private static final class StubResolveSessionParticipant implements ResolveSessionParticipantUseCase {

        private RuntimeException failure;
        private SessionParticipantRole role = SessionParticipantRole.STUDENT;

        @Override
        public ResolveSessionParticipantResult resolve(ResolveSessionParticipantQuery query) {
            if (failure != null) {
                throw failure;
            }
            return new ResolveSessionParticipantResult(
                    STUDENT_PARTICIPANT, role, SESSION_STARTED_AT, SESSION_EXPIRES_AT);
        }
    }

    private static final class InMemoryCheckPromptRepository implements CheckPromptRepository {

        private final List<CheckPrompt> saved = new ArrayList<>();
        private boolean failSave;

        @Override
        public Optional<CheckPrompt> findByParticipantAndKindAndShownOffset(
                Long sessionId, Long participantId, PromptKind kind, long shownOffsetMs) {
            return saved.stream()
                    .filter(prompt -> prompt.sessionId().equals(sessionId)
                            && prompt.participantId().equals(participantId)
                            && prompt.kind() == kind
                            && prompt.shownOffsetMs() == shownOffsetMs)
                    .findFirst();
        }

        @Override
        public CheckPrompt save(CheckPrompt checkPrompt) {
            if (failSave) {
                throw new IllegalStateException("db down");
            }
            saved.add(checkPrompt);
            return checkPrompt;
        }
    }

    private static final class InMemoryAttentionStatePort implements AttentionStatePort {

        private final Map<Long, AttentionSnapshot> currentState = new HashMap<>();
        private final Set<AttentionState> significant = new HashSet<>();
        private final Set<Long> excluded = new HashSet<>();
        private Duration exclusionTtl;
        private boolean failWrites;

        private final Set<String> markers = new HashSet<>();
        private boolean failMarker;

        @Override
        public boolean registerEvent(long sessionId, long participantId, String clientEventId, Duration ttl) {
            if (failMarker) {
                throw new AttentionStateUnavailableException(new IllegalStateException("redis down"));
            }
            return markers.add(participantId + ":" + clientEventId);
        }

        @Override
        public void clearEvent(long sessionId, long participantId, String clientEventId) {
            markers.remove(participantId + ":" + clientEventId);
        }

        @Override
        public void recordCurrentState(long sessionId, long participantId, AttentionSnapshot snapshot, Duration ttl) {
            failFast();
            currentState.put(participantId, snapshot);
        }

        @Override
        public void markSignificant(long sessionId, long participantId, AttentionState state, Duration window) {
            failFast();
            significant.add(state);
        }

        @Override
        public void excludeFromDenominator(long sessionId, long participantId, Duration ttl) {
            failFast();
            excluded.add(participantId);
            exclusionTtl = ttl;
        }

        @Override
        public void includeInDenominator(long sessionId, long participantId) {
            failFast();
            excluded.remove(participantId);
        }

        private boolean runsReset;

        @Override
        public Optional<ObservationApplied> applyObservation(
                long sessionId,
                long participantId,
                DetectionRunTransition transition,
                boolean measurementSuspended,
                long observedOffsetMs,
                Duration ttl) {
            return Optional.of(new ObservationApplied(DetectionRunCounters.none(), OptionalLong.empty()));
        }

        @Override
        public void resetRuns(long sessionId, long participantId) {
            failFast();
            runsReset = true;
        }

        private void failFast() {
            if (failWrites) {
                throw new IllegalStateException("redis down");
            }
        }
    }
}
