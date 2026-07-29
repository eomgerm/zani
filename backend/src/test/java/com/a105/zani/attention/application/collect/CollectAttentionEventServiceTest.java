package com.a105.zani.attention.application.collect;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.application.exception.InvalidDetectionTimelineException;
import com.a105.zani.attention.application.exception.NotSessionStudentException;
import com.a105.zani.attention.application.exception.UnsupportedDetectorContractException;
import com.a105.zani.attention.application.port.AttentionSnapshot;
import com.a105.zani.attention.application.port.AttentionStatePort;
import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.DetectionRecord;
import com.a105.zani.attention.domain.model.DetectionRunCounters;
import com.a105.zani.attention.domain.model.DetectionRunTransition;
import com.a105.zani.attention.domain.model.DetectionSignal;
import com.a105.zani.attention.domain.model.DetectorOutcome;
import com.a105.zani.attention.domain.model.RunStep;
import com.a105.zani.attention.domain.repository.DetectionRecordRepository;
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

class CollectAttentionEventServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long STUDENT_USER = 8L;
    private static final long STUDENT_PARTICIPANT = 2L;
    private static final Instant SESSION_STARTED_AT = Instant.parse("2026-07-28T09:00:00Z");
    private static final Instant SERVER_NOW = SESSION_STARTED_AT.plusSeconds(600);
    private static final String SCHEMA = "mediapipe_98_v1";
    private static final String ENGINE = "e0g-1";

    private final InMemoryAttentionStatePort statePort = new InMemoryAttentionStatePort();
    private final InMemoryDetectionRecordRepository records = new InMemoryDetectionRecordRepository();
    private final StubResolveSessionParticipant resolveParticipant = new StubResolveSessionParticipant();

    private CollectAttentionEventService service;

    /** 판정이 10초마다 도착하도록 창을 순서대로 만든다. */
    private int window = 0;

    @BeforeEach
    void setUp() {
        service = new CollectAttentionEventService(
                resolveParticipant,
                records,
                statePort,
                new DetectorContractProperties(Set.of(SCHEMA), Set.of()),
                Clock.fixed(SERVER_NOW, ZoneOffset.UTC));
        window = 0;
    }

    @Test
    void keepsEveryObservationAsEvidenceEvenWhenItConfirmsNoState() {
        // 저참여 한 건은 아직 학생 상태를 정하지 않지만 기록은 남아야 한다.
        service.collect(next(DetectorOutcome.NOT_ENGAGED));

        assertEquals(1, records.saved.size());
        DetectionRecord saved = records.saved.get(0);
        assertEquals(DetectorOutcome.NOT_ENGAGED, saved.signal().outcome());
        assertEquals(SCHEMA, saved.featureSchemaVersion());
        assertEquals(ENGINE, saved.engineVersion());
        assertTrue(statePort.currentState.isEmpty());
    }

    @Test
    void confirmsGoodImmediatelyForAnEngagedObservation() {
        service.collect(next(DetectorOutcome.ENGAGED));

        assertEquals(
                AttentionState.GOOD,
                statePort.currentState.get(STUDENT_PARTICIPANT).state());
        assertTrue(statePort.significant.isEmpty());
    }

    @Test
    void confirmsCameraOffImmediatelyWithoutWaitingForARun() {
        service.collect(immediate(DetectorOutcome.CAMERA_OFF));

        assertEquals(
                AttentionState.CAMERA_OFF,
                statePort.currentState.get(STUDENT_PARTICIPANT).state());
        // 카메라를 끈 학생을 문제 있는 학생으로 세면 사실상 카메라를 강제하는 셈이다(§7.2).
        assertTrue(statePort.significant.isEmpty());
    }

    @Test
    void doesNotCountASingleUnmeasurableWindowAsAStudentState() {
        service.collect(next(DetectorOutcome.UNMEASURABLE));

        // 고개를 크게 돌리거나 자세를 고쳐 앉는 것만으로 한 창이 UNMEASURABLE 이 된다.
        assertTrue(statePort.currentState.isEmpty());
        assertTrue(statePort.significant.isEmpty());
    }

    @Test
    void confirmsUnmeasurableOnlyAfterThreeConsecutiveWindows() {
        service.collect(next(DetectorOutcome.UNMEASURABLE));
        service.collect(next(DetectorOutcome.UNMEASURABLE));
        assertTrue(statePort.significant.isEmpty());

        service.collect(next(DetectorOutcome.UNMEASURABLE));

        assertEquals(
                AttentionState.UNMEASURABLE,
                statePort.currentState.get(STUDENT_PARTICIPANT).state());
        assertTrue(statePort.significant.contains(AttentionState.UNMEASURABLE));
    }

    @Test
    void restartsTheUnmeasurableRunWhenTheFaceIsSeenAgain() {
        service.collect(next(DetectorOutcome.UNMEASURABLE));
        service.collect(next(DetectorOutcome.UNMEASURABLE));
        service.collect(next(DetectorOutcome.ENGAGED));
        service.collect(next(DetectorOutcome.UNMEASURABLE));

        assertTrue(statePort.significant.isEmpty());
    }

    @Test
    void leavesTheStateOpenWhileTheLowEngagementRunBuilds() {
        service.collect(next(DetectorOutcome.NOT_ENGAGED));
        service.collect(next(DetectorOutcome.BARELY_ENGAGED));
        service.collect(next(DetectorOutcome.NOT_ENGAGED));

        // 저참여 3연속은 프롬프트를 띄울 조건일 뿐, 상태는 이해 확인 응답이 와야 갈린다(§2).
        assertTrue(statePort.currentState.isEmpty());
        assertTrue(statePort.significant.isEmpty());
        assertEquals(3, statePort.lastCounters.lowEngagement());
    }

    @Test
    void keepsTheLowEngagementRunAcrossAnUnmeasurableWindow() {
        service.collect(next(DetectorOutcome.NOT_ENGAGED));
        service.collect(next(DetectorOutcome.NOT_ENGAGED));
        service.collect(next(DetectorOutcome.UNMEASURABLE));
        service.collect(next(DetectorOutcome.NOT_ENGAGED));

        assertTrue(statePort.lastCounters.lowEngagementRunComplete());
    }

    @Test
    void ignoresARetryThatCarriesAnAlreadyProcessedClientEventId() {
        CollectAttentionEventCommand command = next(DetectorOutcome.ENGAGED);
        service.collect(command);
        records.saved.clear();

        CollectAttentionEventResult retry = service.collect(command);

        assertTrue(retry.duplicate());
        assertFalse(retry.accepted());
        assertTrue(records.saved.isEmpty());
    }

    @Test
    void keepsALateObservationAsEvidenceWithoutRewindingTheCoachingState() {
        service.collect(next(DetectorOutcome.ENGAGED));
        service.collect(next(DetectorOutcome.ENGAGED));
        // 두 창 뒤에 첫 창이 도착한다. 같은 시각이 아니라 엄격히 과거다.
        CollectAttentionEventCommand late = at(DetectorOutcome.NOT_ENGAGED, 1);

        CollectAttentionEventResult result = service.collect(late);

        assertTrue(result.supersededByNewerJudgement());
        assertEquals(3, records.saved.size());
        // 옛 판정이 최신 상태를 덮어쓰면 학생이 과거로 되돌아간다.
        assertEquals(
                AttentionState.GOOD,
                statePort.currentState.get(STUDENT_PARTICIPANT).state());
    }

    @Test
    void rejectsAnObservationFromBeforeTheSessionStarted() {
        CollectAttentionEventCommand beforeStart = new CollectAttentionEventCommand(
                SESSION_ID,
                STUDENT_USER,
                new DetectionSignal(DetectorOutcome.ENGAGED, false),
                SESSION_STARTED_AT.minusSeconds(20),
                SESSION_STARTED_AT.minusSeconds(10),
                0.9d,
                SCHEMA,
                ENGINE,
                "before-start");

        assertThrows(InvalidDetectionTimelineException.class, () -> service.collect(beforeStart));
        assertTrue(records.saved.isEmpty());
    }

    @Test
    void rejectsAnObservationWhoseWindowEndsBeforeItStarts() {
        CollectAttentionEventCommand backwards = new CollectAttentionEventCommand(
                SESSION_ID,
                STUDENT_USER,
                new DetectionSignal(DetectorOutcome.ENGAGED, false),
                SESSION_STARTED_AT.plusSeconds(60),
                SESSION_STARTED_AT.plusSeconds(50),
                0.9d,
                SCHEMA,
                ENGINE,
                "backwards");

        assertThrows(InvalidDetectionTimelineException.class, () -> service.collect(backwards));
    }

    @Test
    void rejectsAnObservationFromAClockThatRunsFarAhead() {
        CollectAttentionEventCommand future = new CollectAttentionEventCommand(
                SESSION_ID,
                STUDENT_USER,
                new DetectionSignal(DetectorOutcome.ENGAGED, false),
                SERVER_NOW.plusSeconds(3_600),
                SERVER_NOW.plusSeconds(3_610),
                0.9d,
                SCHEMA,
                ENGINE,
                "future");

        assertThrows(InvalidDetectionTimelineException.class, () -> service.collect(future));
    }

    @Test
    void rejectsAnObservationFromAnUnsupportedFeatureSchema() {
        CollectAttentionEventCommand otherSchema = new CollectAttentionEventCommand(
                SESSION_ID,
                STUDENT_USER,
                new DetectionSignal(DetectorOutcome.ENGAGED, false),
                SESSION_STARTED_AT,
                SESSION_STARTED_AT.plusSeconds(10),
                0.9d,
                "mediapipe_64_v0",
                ENGINE,
                "other-schema");

        // 다른 잣대로 잰 판정을 같은 집계에 섞으면 안 된다.
        assertThrows(UnsupportedDetectorContractException.class, () -> service.collect(otherSchema));
        assertTrue(records.saved.isEmpty());
    }

    @Test
    void letsTheObservationBeSentAgainWhenStoringItFailed() {
        records.failSave = true;

        assertThrows(IllegalStateException.class, () -> service.collect(next(DetectorOutcome.ENGAGED)));

        // 멱등 표시만 남으면 재시도가 거짓 성공을 받고 그 관측이 영영 사라진다.
        assertTrue(statePort.seenEvents.isEmpty());
    }

    @Test
    void doesNotCountTheSameObservationTwiceWhenItsMarkerHasAlreadyExpired() {
        CollectAttentionEventCommand observation = next(DetectorOutcome.NOT_ENGAGED);
        service.collect(observation);
        service.collect(next(DetectorOutcome.NOT_ENGAGED));
        statePort.seenEvents.clear(); // 멱등 표시의 TTL 이 지난 뒤 도착한 재시도

        CollectAttentionEventResult retry = service.collect(observation);

        // 유니크 제약이 행은 막고, 더 최신 관측이 이미 반영됐다는 사실이 카운터를 지킨다.
        assertTrue(retry.supersededByNewerJudgement());
        assertEquals(2, records.saved.size());
        assertEquals(2, statePort.lastCounters.lowEngagement());
    }

    @Test
    void finishesTheCoachingUpdateWhenAnEarlierAttemptDiedAfterStoringTheRow() {
        CollectAttentionEventCommand observation = next(DetectorOutcome.ENGAGED);
        statePort.failAdvance = true;
        assertThrows(IllegalStateException.class, () -> service.collect(observation));

        statePort.failAdvance = false;
        CollectAttentionEventResult retry = service.collect(observation);

        // 행이 이미 있다고 돌려보내면, 저장 뒤 죽은 그 관측의 집계 반영이 영영 되살아나지 못한다.
        assertTrue(retry.accepted());
        assertEquals(1, records.saved.size());
        assertEquals(
                AttentionState.GOOD,
                statePort.currentState.get(STUDENT_PARTICIPANT).state());
    }

    @Test
    void rejectsAnObservationSentByTheInstructor() {
        resolveParticipant.role = SessionParticipantRole.INSTRUCTOR;

        assertThrows(NotSessionStudentException.class, () -> service.collect(next(DetectorOutcome.ENGAGED)));
        assertTrue(records.saved.isEmpty());
    }

    @Test
    void rejectsAnObservationFromSomeoneWhoIsNotASessionMember() {
        resolveParticipant.failure = new NotSessionMemberException();

        assertThrows(NotSessionMemberException.class, () -> service.collect(next(DetectorOutcome.ENGAGED)));
        assertTrue(records.saved.isEmpty());
    }

    @Test
    void storesTheDetectorUnavailableObservationWithoutConfirmingAStudentState() {
        service.collect(immediate(DetectorOutcome.DETECTOR_UNAVAILABLE));

        // 분모 제외 판단에만 쓰이는 값이라 학생 상태를 만들지 않는다(§2.1).
        assertEquals(1, records.saved.size());
        assertNull(statePort.currentState.get(STUDENT_PARTICIPANT));
        assertTrue(statePort.significant.isEmpty());
    }

    /** 10초씩 앞으로 나아가는 창 하나. */
    private CollectAttentionEventCommand next(DetectorOutcome outcome) {
        return at(outcome, ++window);
    }

    private CollectAttentionEventCommand at(DetectorOutcome outcome, int windowIndex) {
        Instant start = SESSION_STARTED_AT.plusSeconds(10L * (windowIndex - 1));
        return new CollectAttentionEventCommand(
                SESSION_ID,
                STUDENT_USER,
                new DetectionSignal(
                        outcome, outcome == DetectorOutcome.NOT_ENGAGED || outcome == DetectorOutcome.BARELY_ENGAGED),
                start,
                start.plusSeconds(10),
                0.92d,
                SCHEMA,
                ENGINE,
                "event-" + windowIndex + "-" + outcome);
    }

    /** 창 없이 그 자리에서 확정되는 출력(§4.2). */
    private CollectAttentionEventCommand immediate(DetectorOutcome outcome) {
        window++;
        return new CollectAttentionEventCommand(
                SESSION_ID,
                STUDENT_USER,
                new DetectionSignal(outcome, false),
                null,
                SESSION_STARTED_AT.plusSeconds(10L * window),
                null,
                SCHEMA,
                ENGINE,
                "event-" + window + "-" + outcome);
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
                    STUDENT_PARTICIPANT, role, SESSION_STARTED_AT, SESSION_STARTED_AT.plusSeconds(10_800));
        }
    }

    private static final class InMemoryDetectionRecordRepository implements DetectionRecordRepository {

        private final List<DetectionRecord> saved = new ArrayList<>();
        private final Set<String> stored = new HashSet<>();
        private boolean failSave;

        @Override
        public boolean saveIfNew(DetectionRecord record) {
            if (failSave) {
                throw new IllegalStateException("db down");
            }
            // 실제 어댑터는 (세션, 참가자, clientEventId) 유니크 제약을 INSERT IGNORE 로 흘려보내고 0을 돌려준다.
            if (!stored.add(record.participantId() + ":" + record.clientEventId())) {
                return false;
            }
            return saved.add(record);
        }
    }

    private static final class InMemoryAttentionStatePort implements AttentionStatePort {

        private final Set<String> seenEvents = new HashSet<>();
        private final Map<Long, AttentionSnapshot> currentState = new HashMap<>();
        private final Set<AttentionState> significant = new HashSet<>();
        private final Set<Long> excluded = new HashSet<>();
        private final Map<Long, Long> appliedOffsets = new HashMap<>();
        private DetectionRunCounters counters = DetectionRunCounters.none();
        private DetectionRunCounters lastCounters = DetectionRunCounters.none();
        private boolean failAdvance;

        @Override
        public boolean registerEvent(long sessionId, long participantId, String clientEventId, Duration ttl) {
            return seenEvents.add(participantId + ":" + clientEventId);
        }

        @Override
        public void clearEvent(long sessionId, long participantId, String clientEventId) {
            seenEvents.remove(participantId + ":" + clientEventId);
        }

        @Override
        public void recordCurrentState(long sessionId, long participantId, AttentionSnapshot snapshot, Duration ttl) {
            currentState.put(participantId, snapshot);
        }

        @Override
        public void markSignificant(long sessionId, long participantId, AttentionState state, Duration window) {
            significant.add(state);
        }

        @Override
        public void excludeFromDenominator(long sessionId, long participantId, Duration ttl) {
            excluded.add(participantId);
        }

        @Override
        public void includeInDenominator(long sessionId, long participantId) {
            excluded.remove(participantId);
        }

        @Override
        public DetectionRunCounters advanceRun(
                long sessionId, long participantId, DetectionRunTransition transition, Duration ttl) {
            if (failAdvance) {
                throw new IllegalStateException("redis down");
            }
            counters = new DetectionRunCounters(
                    apply(transition.lowEngagement(), counters.lowEngagement()),
                    apply(transition.unmeasurable(), counters.unmeasurable()));
            lastCounters = counters;
            return counters;
        }

        @Override
        public void resetRuns(long sessionId, long participantId) {
            counters = DetectionRunCounters.none();
            lastCounters = counters;
        }

        private int apply(RunStep step, int current) {
            return switch (step) {
                case INCREMENT -> current + 1;
                case RESET -> 0;
                case KEEP -> current;
            };
        }

        @Override
        public OptionalLong lastAppliedOffsetMs(long sessionId, long participantId) {
            Long value = appliedOffsets.get(participantId);
            return value == null ? OptionalLong.empty() : OptionalLong.of(value);
        }

        @Override
        public void recordAppliedOffsetMs(long sessionId, long participantId, long offsetMs, Duration ttl) {
            appliedOffsets.put(participantId, offsetMs);
        }
    }
}
