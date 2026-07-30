package com.a105.zani.attention.application.polltip;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.a105.zani.attention.application.getcoachingsignals.GetCoachingSignalsResult;
import com.a105.zani.attention.application.getcoachingsignals.GetCoachingSignalsUseCase;
import com.a105.zani.attention.application.port.CoachingOutcome;
import com.a105.zani.attention.application.port.CoachingTip;
import com.a105.zani.attention.application.port.CoachingTipPipelinePort;
import com.a105.zani.attention.application.port.CoachingTipRequest;
import com.a105.zani.attention.application.port.CoachingTipType;
import com.a105.zani.attention.application.port.CoachingTipUnavailableReason;
import com.a105.zani.attention.application.port.CoachingTriggerStatePort;
import com.a105.zani.attention.application.port.PreviousCoachingTip;
import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.CoachingSignalSummary;
import com.a105.zani.attention.domain.model.CoachingTriggerPolicy;
import com.a105.zani.audioclip.application.port.AudioClip;
import com.a105.zani.audioclip.application.port.InstructorAudioBufferPort;
import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantQuery;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantResult;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollCoachingTipServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long INSTRUCTOR_USER = 7L;
    private static final long STUDENT_USER = 8L;
    private static final Instant NOW = Instant.parse("2026-07-29T09:30:00Z");
    private static final Duration COOLDOWN = Duration.ofMinutes(10);

    private final CoachingTriggerPolicy policy = new CoachingTriggerPolicy(0.30, COOLDOWN, Duration.ofMinutes(1));
    private final InMemoryCoachingTriggerState state = new InMemoryCoachingTriggerState();
    private final RecordingPipeline pipeline = new RecordingPipeline();
    private final StubResolveSessionParticipant resolveParticipant = new StubResolveSessionParticipant();

    /** 유의 학생 비율. 기본값은 임계를 넘는다. */
    private CoachingSignalSummary summary = summary(10, 4);

    /** 확보된 강사 오디오. 기본값은 최소 길이를 넘는다. */
    private long availableMs = Duration.ofMinutes(2).toMillis();

    private PollCoachingTipService service;

    private static CoachingSignalSummary summary(int denominator, int numerator) {
        return new CoachingSignalSummary(
                denominator, numerator, numerator == 0 ? Map.of() : Map.of(AttentionState.CONFUSED, numerator));
    }

    @BeforeEach
    void setUp() {
        service = serviceWith(pipeline);
    }

    private PollCoachingTipService serviceWith(CoachingTipPipelinePort tipPipeline) {
        GetCoachingSignalsUseCase signals = query -> {
            assertEquals(SESSION_ID, query.sessionId());
            return new GetCoachingSignalsResult(summary);
        };
        InstructorAudioBufferPort buffer = new InstructorAudioBufferPort() {
            @Override
            public Optional<AudioClip> snapshot(long sessionId, Duration window) {
                throw new UnsupportedOperationException("트리거 판정은 오디오를 떠내지 않는다.");
            }

            @Override
            public long availableMs(long sessionId) {
                return availableMs;
            }

            @Override
            public void release(long sessionId) {
                throw new UnsupportedOperationException();
            }
        };
        return new PollCoachingTipService(
                resolveParticipant,
                signals,
                buffer,
                state,
                policy,
                Clock.fixed(NOW, ZoneOffset.UTC),
                provider(tipPipeline));
    }

    private PollCoachingTipResult poll() {
        return service.poll(new PollCoachingTipCommand(SESSION_ID, INSTRUCTOR_USER));
    }

    @Test
    void opensATriggerAndAnswersBeforeTheTipExists() {
        PollCoachingTipResult result = poll();

        CoachingOutcome outcome = result.outcome().orElseThrow();
        // 전사 20초와 LLM 6초를 응답 안에서 기다리면 강사의 10초 주기가 26초 동안 막힌다.
        assertNull(outcome.tip());
        assertNull(outcome.unavailableReason());
        assertEquals(1, pipeline.started.size());
        assertEquals(outcome.triggerId(), pipeline.started.get(0).triggerId());
    }

    @Test
    void answersWithoutRecomputingWhileTheCooldownHolds() {
        String triggerId = poll().outcome().orElseThrow().triggerId();
        summary = summary(10, 9);

        PollCoachingTipResult second = poll();

        // 같은 트리거를 그대로 돌려준다. 프론트는 triggerId 로 중복 표시를 막는다.
        assertEquals(triggerId, second.outcome().orElseThrow().triggerId());
        assertEquals(1, pipeline.started.size());
    }

    @Test
    void opensANewTriggerOnceTheCooldownHasElapsed() {
        String first = poll().outcome().orElseThrow().triggerId();
        state.expire();

        String second = poll().outcome().orElseThrow().triggerId();

        assertFalse(first.equals(second));
        assertEquals(2, pipeline.started.size());
    }

    @Test
    void startsTheCooldownEvenWhenTheTipCouldNotBeMade() {
        String triggerId = poll().outcome().orElseThrow().triggerId();
        state.completeOutcome(
                SESSION_ID, CoachingOutcome.unavailable(triggerId, CoachingTipUnavailableReason.TRANSCRIPTION_FAILED));

        PollCoachingTipResult second = poll();

        // 성공했을 때만 쿨타임을 시작하면 비율이 임계 이상인 동안 10초마다 GMS 를 다시 부른다.
        assertEquals(
                CoachingTipUnavailableReason.TRANSCRIPTION_FAILED,
                second.outcome().orElseThrow().unavailableReason());
        assertEquals(1, pipeline.started.size());
    }

    @Test
    void doesNotOpenATriggerWhenNobodyIsCounted() {
        summary = summary(0, 0);

        assertTrue(poll().outcome().isEmpty());
        assertTrue(pipeline.started.isEmpty());
        assertTrue(state.stored.isEmpty());
    }

    @Test
    void doesNotOpenATriggerBelowTheThreshold() {
        summary = summary(100, 29);

        assertTrue(poll().outcome().isEmpty());
        assertTrue(pipeline.started.isEmpty());
    }

    @Test
    void leavesNoCooldownWhenTheInstructorAudioIsTooShort() {
        availableMs = Duration.ofSeconds(59).toMillis();

        assertTrue(poll().outcome().isEmpty());
        // GMS 를 부르지 않았으므로 쿨타임도 시작하지 않는다(§7). 시작하면 수업 시작 직후 10분이 조용해진다.
        assertTrue(state.stored.isEmpty());
        assertTrue(pipeline.started.isEmpty());
    }

    @Test
    void doesNotOpenASecondTriggerWhenAnotherPollWonTheRace() {
        String other = "trigger-from-another-window";
        state.claimBefore = () -> state.stored.put(SESSION_ID, CoachingOutcome.pending(other));

        PollCoachingTipResult result = poll();

        assertEquals(other, result.outcome().orElseThrow().triggerId());
        assertTrue(pipeline.started.isEmpty());
    }

    @Test
    void handsThePreviousTipToTheGeneratorSoItDoesNotRepeatItself() {
        state.previous = new PreviousCoachingTip(CoachingTipType.MISSED, NOW.minus(Duration.ofMinutes(11)));

        poll();

        assertEquals(
                CoachingTipType.MISSED, pipeline.started.get(0).previousTip().tipType());
    }

    @Test
    void handsTheRatiosOutAsSeparateFieldsWithTheSampleSize() {
        summary = new CoachingSignalSummary(
                10, 5, Map.of(AttentionState.CONFUSED, 3, AttentionState.MISSED, 2, AttentionState.UNMEASURABLE, 1));

        poll();

        CoachingTipRequest request = pipeline.started.get(0);
        assertEquals(10, request.studentsCounted());
        assertEquals(0.5, request.significantRatio());
        assertEquals(0.3, request.confusedRatio());
        assertEquals(0.2, request.missedRatio());
        // Map 으로 넘기면 키가 빠져도 조용히 0 이 되어 집계가 깨진 것과 구분되지 않는다.
        assertEquals(0.0, request.nonResponseRatio());
        assertEquals(0.1, request.unmeasurableRatio());
        assertEquals(NOW, request.triggeredAt());
    }

    @Test
    void stillOpensTheTriggerWhenTheGeneratorIsNotDeployedYet() {
        service = serviceWith(null);

        // 되돌리면 파이프라인이 붙기 전까지 10초마다 트리거를 새로 연다.
        assertTrue(poll().outcome().isPresent());
        assertFalse(state.stored.isEmpty());
    }

    @Test
    void stillAnswersWhenTheGeneratorThrowsOnTheCallerThread() {
        service = serviceWith(request -> {
            throw new IllegalStateException("파이프라인이 즉시 반환하지 않고 던졌다");
        });

        // 예외가 새어 나가면 강사 폴링이 500 이 되고, 76 이 그것을 연속 실패로 세어 코칭 비활성을 띄운다.
        assertTrue(poll().outcome().isPresent());
        // 쿨타임은 되돌리지 않는다. 되돌리면 파이프라인이 계속 던지는 동안 10초마다 트리거를 새로 연다.
        assertFalse(state.stored.isEmpty());
    }

    @Test
    void ignoresAResultFromATriggerThatIsNoLongerOpen() {
        String stale = poll().outcome().orElseThrow().triggerId();
        state.expire();
        String current = poll().outcome().orElseThrow().triggerId();

        state.completeOutcome(
                SESSION_ID, CoachingOutcome.unavailable(stale, CoachingTipUnavailableReason.NO_TRANSCRIPT));

        // 늦게 끝난 트리거가 새 트리거를 덮어쓰면 강사는 지난 구간의 결과를 이전 triggerId 로 받는다.
        CoachingOutcome open = poll().outcome().orElseThrow();
        assertEquals(current, open.triggerId());
        assertNull(open.unavailableReason());
    }

    @Test
    void rejectsAPollFromAStudent() {
        // 학생에게 집단 통계를 보여 주면 익명 집계를 지켜 온 의미가 사라진다.
        assertThrows(
                NotSessionInstructorException.class,
                () -> service.poll(new PollCoachingTipCommand(SESSION_ID, STUDENT_USER)));
        assertTrue(state.stored.isEmpty());
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    private static final class StubResolveSessionParticipant implements ResolveSessionParticipantUseCase {

        @Override
        public ResolveSessionParticipantResult resolve(ResolveSessionParticipantQuery query) {
            SessionParticipantRole role = query.userId() == INSTRUCTOR_USER
                    ? SessionParticipantRole.INSTRUCTOR
                    : SessionParticipantRole.STUDENT;
            return new ResolveSessionParticipantResult(
                    query.userId(), role, NOW.minus(Duration.ofMinutes(30)), NOW.plus(Duration.ofHours(2)));
        }
    }

    /** 키의 생존이 곧 쿨타임인 저장소를 흉내 낸다. TTL 대신 {@link #expire()} 로 만료를 표현한다. */
    private static final class InMemoryCoachingTriggerState implements CoachingTriggerStatePort {

        private final Map<Long, CoachingOutcome> stored = new HashMap<>();
        private PreviousCoachingTip previous;

        /** 원자적 claim 직전에 다른 요청이 먼저 여는 상황을 만든다. */
        private Runnable claimBefore;

        void expire() {
            stored.clear();
        }

        @Override
        public boolean openTrigger(long sessionId, String triggerId, Duration cooldown) {
            if (claimBefore != null) {
                claimBefore.run();
                claimBefore = null;
            }
            if (stored.containsKey(sessionId)) {
                return false;
            }
            stored.put(sessionId, CoachingOutcome.pending(triggerId));
            return true;
        }

        @Override
        public Optional<CoachingOutcome> openOutcome(long sessionId) {
            return Optional.ofNullable(stored.get(sessionId));
        }

        @Override
        public void completeOutcome(long sessionId, CoachingOutcome outcome) {
            // 실제 어댑터와 같게, 지금 열려 있는 트리거가 이 결과의 것일 때만 채운다. 키만 보면 늦게 끝난 트리거가 다음 트리거를 덮어쓴다.
            CoachingOutcome open = stored.get(sessionId);
            if (open == null || !open.triggerId().equals(outcome.triggerId())) {
                return;
            }
            stored.put(sessionId, outcome);
            CoachingTip tip = outcome.tip();
            if (tip != null) {
                previous = new PreviousCoachingTip(tip.tipType(), NOW);
            }
        }

        @Override
        public Optional<PreviousCoachingTip> previousTip(long sessionId) {
            return Optional.ofNullable(previous);
        }
    }

    private static final class RecordingPipeline implements CoachingTipPipelinePort {

        private final List<CoachingTipRequest> started = new ArrayList<>();

        @Override
        public void start(CoachingTipRequest request) {
            started.add(request);
        }
    }
}
