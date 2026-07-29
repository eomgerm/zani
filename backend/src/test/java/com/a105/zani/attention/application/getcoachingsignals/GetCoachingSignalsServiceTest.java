package com.a105.zani.attention.application.getcoachingsignals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.application.getdenominator.GetDenominatorQuery;
import com.a105.zani.attention.application.getdenominator.GetDenominatorResult;
import com.a105.zani.attention.application.getdenominator.GetDenominatorUseCase;
import com.a105.zani.attention.application.port.AttentionSnapshot;
import com.a105.zani.attention.application.port.AttentionStatePort;
import com.a105.zani.attention.application.port.ObservationApplied;
import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.CoachingDenominator;
import com.a105.zani.attention.domain.model.CoachingSignalSummary;
import com.a105.zani.attention.domain.model.DetectionRunTransition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 확정 문서 §7·§7.3 의 분자 계산을 고정한다. 합집합·익명·분모 0 경계가 대상이다. */
class GetCoachingSignalsServiceTest {

    private static final long SESSION_ID = 100L;

    private final StubDenominator denominator = new StubDenominator();
    private final InMemoryAttentionStatePort statePort = new InMemoryAttentionStatePort();

    private GetCoachingSignalsService service;

    @BeforeEach
    void setUp() {
        service = new GetCoachingSignalsService(denominator, statePort);
    }

    @Test
    void countsAStudentOnceEvenWhenTwoSignificantStatesApply() {
        denominator.counting(1L, 2L, 3L, 4L, 5L);
        statePort.mark(AttentionState.CONFUSED, 1L, 2L);
        statePort.mark(AttentionState.UNMEASURABLE, 2L, 3L);

        CoachingSignalSummary summary = summary();

        // 학생 2번이 두 상태에 있다. 상태별로 세어 더하면 4명이 되어 비율이 부풀어 오른다.
        assertEquals(3, summary.numerator());
        assertEquals(5, summary.denominator());
        assertEquals(2, summary.countOf(AttentionState.CONFUSED));
        assertEquals(2, summary.countOf(AttentionState.UNMEASURABLE));
    }

    @Test
    void leavesOutStatesThatCannotEnterTheNumerator() {
        denominator.counting(1L, 2L);
        statePort.mark(AttentionState.GOOD, 1L);
        statePort.mark(AttentionState.CAMERA_OFF, 2L);

        CoachingSignalSummary summary = summary();

        // GOOD 은 문제가 없다는 뜻이고, CAMERA_OFF 는 분자가 아니라 분모 제외 대상이다(§7.2).
        assertEquals(0, summary.numerator());
        assertEquals(0.0d, summary.ratio().getAsDouble());
    }

    @Test
    void reportsAnEmptySummaryWhenTheDenominatorIsZero() {
        CoachingSignalSummary summary = summary();

        // 분모가 0이면 비율을 계산하지 않는다. 트리거는 판단을 미룬다(§7).
        assertTrue(summary.ratio().isEmpty());
        assertEquals(0, summary.denominator());
    }

    @Test
    void doesNotAskTheStoreWhenThereIsNobodyToCount() {
        service.get(new GetCoachingSignalsQuery(SESSION_ID));

        assertTrue(statePort.askedAbout.isEmpty());
    }

    @Test
    void separatesTwentyNinePercentFromThirty() {
        denominator.counting(LongStream.rangeClosed(1L, 100L).boxed().toArray(Long[]::new));
        statePort.mark(
                AttentionState.MISSED, LongStream.rangeClosed(1L, 29L).boxed().toArray(Long[]::new));

        assertTrue(summary().ratio().getAsDouble() < 0.30d);

        statePort.mark(AttentionState.MISSED, 30L);

        assertTrue(summary().ratio().getAsDouble() >= 0.30d);
    }

    @Test
    void asksTheStoreOnlyAboutStudentsInTheDenominator() {
        denominator.counting(1L, 2L);

        service.get(new GetCoachingSignalsQuery(SESSION_ID));

        // 분모에서 빠진 학생의 유의 상태를 세면 비율의 분자와 분모가 서로 다른 집합이 된다.
        assertEquals(List.of(Set.of(1L, 2L)), statePort.askedAbout);
    }

    @Test
    void keepsStudentIdentifiersOutOfTheResult() {
        denominator.counting(777L);
        statePort.mark(AttentionState.CONFUSED, 777L);

        GetCoachingSignalsResult result = service.get(new GetCoachingSignalsQuery(SESSION_ID));

        // 이 결과는 LLM 입력(204)과 리포트(205)로 흘러간다. 학생 식별자가 실리면 그대로 따라간다.
        assertFalse(result.toString().contains("777"));
        assertEquals(1, result.summary().numerator());
    }

    private CoachingSignalSummary summary() {
        return service.get(new GetCoachingSignalsQuery(SESSION_ID)).summary();
    }

    private static final class StubDenominator implements GetDenominatorUseCase {

        private Set<Long> counted = Set.of();

        private void counting(Long... participantIds) {
            counted = Set.of(participantIds);
        }

        @Override
        public GetDenominatorResult get(GetDenominatorQuery query) {
            return new GetDenominatorResult(new CoachingDenominator(counted), counted.size());
        }
    }

    private static final class InMemoryAttentionStatePort implements AttentionStatePort {

        private final Map<AttentionState, Set<Long>> marked = new EnumMap<>(AttentionState.class);
        private final List<Set<Long>> askedAbout = new ArrayList<>();

        private void mark(AttentionState state, Long... participantIds) {
            marked.computeIfAbsent(state, key -> new HashSet<>()).addAll(Set.of(participantIds));
        }

        @Override
        public Map<AttentionState, Set<Long>> significantParticipants(long sessionId, Collection<Long> participantIds) {
            askedAbout.add(Set.copyOf(participantIds));
            Map<AttentionState, Set<Long>> found = new EnumMap<>(AttentionState.class);
            for (AttentionState state : AttentionState.values()) {
                if (!state.isSignificant()) {
                    continue;
                }
                // 실제 어댑터는 유의 상태 키만 읽으므로, 물어본 참가자 중 표시가 있는 사람만 돌려준다.
                found.put(
                        state,
                        marked.getOrDefault(state, Set.of()).stream()
                                .filter(participantIds::contains)
                                .collect(Collectors.toSet()));
            }
            return found;
        }

        @Override
        public Set<Long> excludedFromDenominator(long sessionId, Collection<Long> participantIds) {
            return Set.of();
        }

        @Override
        public boolean registerEvent(long sessionId, long participantId, String clientEventId, Duration ttl) {
            return true;
        }

        @Override
        public void clearEvent(long sessionId, long participantId, String clientEventId) {}

        @Override
        public void recordCurrentState(long sessionId, long participantId, AttentionSnapshot snapshot, Duration ttl) {}

        @Override
        public void markSignificant(long sessionId, long participantId, AttentionState state, Duration window) {
            mark(state, participantId);
        }

        @Override
        public void excludeFromDenominator(long sessionId, long participantId, Duration ttl) {}

        @Override
        public void includeInDenominator(long sessionId, long participantId) {}

        @Override
        public Optional<ObservationApplied> applyObservation(
                long sessionId,
                long participantId,
                DetectionRunTransition transition,
                boolean measurementSuspended,
                long observedOffsetMs,
                Duration ttl) {
            return Optional.empty();
        }

        @Override
        public void resetUnmeasurableRun(long sessionId, long participantId) {}
    }
}
