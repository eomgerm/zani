package com.a105.zani.attention.application.collect;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.application.exception.NotSessionStudentException;
import com.a105.zani.attention.application.port.AttentionSnapshot;
import com.a105.zani.attention.application.port.AttentionStatePort;
import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.SessionAlreadyEndedException;
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
    private static final Instant ENDED_AT = Instant.parse("2026-07-28T09:00:10Z");
    private static final Instant SERVER_NOW = Instant.parse("2026-07-28T09:00:11Z");

    private final InMemoryAttentionStatePort statePort = new InMemoryAttentionStatePort();
    private final StubResolveSessionParticipant resolveParticipant = new StubResolveSessionParticipant();

    private CollectAttentionEventService service;

    @BeforeEach
    void setUp() {
        service = new CollectAttentionEventService(
                resolveParticipant, statePort, Clock.fixed(SERVER_NOW, ZoneOffset.UTC));
    }

    @Test
    void storesTheCurrentStateForAResolvedParticipant() {
        CollectAttentionEventResult result = service.collect(command(AttentionState.GOOD, "event-1"));

        assertTrue(result.accepted());
        assertFalse(result.duplicate());
        AttentionSnapshot stored = statePort.currentState.get(STUDENT_PARTICIPANT);
        assertEquals(AttentionState.GOOD, stored.state());
        assertEquals(0.92d, stored.signalQuality());
        // 기록 시각은 클라이언트가 보낸 endedAt 이 아니라 서버 시계여야 한다(시계 틀어짐 방지).
        assertEquals(SERVER_NOW, stored.recordedAt());
    }

    @Test
    void keepsTheCurrentStateTtlLongerThanOneJudgingWindow() {
        service.collect(command(AttentionState.GOOD, "event-1"));

        // 판정 주기는 10초다. TTL이 그보다 짧으면 다음 판정이 오기 전에 상태가 사라진다.
        assertTrue(statePort.currentStateTtl.compareTo(Duration.ofSeconds(10)) > 0);
    }

    @Test
    void ignoresARetryThatCarriesAnAlreadyProcessedClientEventId() {
        service.collect(command(AttentionState.CONFUSED, "event-1"));
        statePort.currentState.clear();

        CollectAttentionEventResult retry = service.collect(command(AttentionState.CONFUSED, "event-1"));

        assertFalse(retry.accepted());
        assertTrue(retry.duplicate());
        assertNull(statePort.currentState.get(STUDENT_PARTICIPANT));
    }

    @Test
    void acceptsTheNextEventAfterADuplicateWasIgnored() {
        service.collect(command(AttentionState.CONFUSED, "event-1"));
        service.collect(command(AttentionState.CONFUSED, "event-1"));

        CollectAttentionEventResult next = service.collect(command(AttentionState.MISSED, "event-2"));

        assertTrue(next.accepted());
        assertEquals(
                AttentionState.MISSED,
                statePort.currentState.get(STUDENT_PARTICIPANT).state());
    }

    @Test
    void marksTheParticipantSignificantForEveryStateThatFeedsTheCoachingTrigger() {
        AttentionState[] significant = {
            AttentionState.CONFUSED, AttentionState.MISSED, AttentionState.NON_RESPONSE, AttentionState.UNMEASURABLE
        };

        for (AttentionState state : significant) {
            statePort.significant.clear();
            service.collect(command(state, "event-" + state));

            assertTrue(statePort.significant.contains(state), state + " should feed the trigger");
        }
    }

    @Test
    void doesNotMarkGoodOrCameraOffAsSignificant() {
        service.collect(command(AttentionState.GOOD, "event-good"));
        service.collect(command(AttentionState.CAMERA_OFF, "event-camera-off"));

        assertTrue(statePort.significant.isEmpty());
    }

    @Test
    void keepsTheSignificantMarkForTheFiveMinuteObservationWindow() {
        service.collect(command(AttentionState.CONFUSED, "event-1"));

        assertEquals(Duration.ofMinutes(5), statePort.significantWindow);
    }

    @Test
    void letsAFailedRetryBeSentAgainInsteadOfClaimingItWasRecorded() {
        statePort.failCurrentStateWrite = true;

        assertThrows(IllegalStateException.class, () -> service.collect(command(AttentionState.GOOD, "event-1")));

        // 되돌리기가 없으면 재시도가 "이미 처리했다"는 거짓 성공을 받고 그 창의 판정이 사라진다.
        assertFalse(statePort.seenEvents.contains(SESSION_ID + ":" + STUDENT_PARTICIPANT + ":event-1"));
    }

    @Test
    void keepsTheEventRecordedWhenOnlyTheTriggerMarkFailed() {
        statePort.failSignificantWrite = true;

        assertThrows(IllegalStateException.class, () -> service.collect(command(AttentionState.CONFUSED, "event-1")));

        // 상태는 이미 남았다. 표시까지 지우면 뒤늦은 재시도가 그 사이 도착한 더 최신 판정을 덮어쓴다.
        assertTrue(statePort.seenEvents.contains(SESSION_ID + ":" + STUDENT_PARTICIPANT + ":event-1"));
        assertEquals(
                AttentionState.CONFUSED,
                statePort.currentState.get(STUDENT_PARTICIPANT).state());
    }

    @Test
    void rejectsAnEventFromAnInstructorBecauseOnlyStudentsAreJudged() {
        resolveParticipant.role = SessionParticipantRole.INSTRUCTOR;

        assertThrows(
                NotSessionStudentException.class, () -> service.collect(command(AttentionState.CONFUSED, "event-1")));

        // 강사 이벤트가 섞이면 코칭 비율의 분자에만 끼어 실제보다 높게 나온다.
        assertTrue(statePort.currentState.isEmpty());
        assertTrue(statePort.significant.isEmpty());
        assertTrue(statePort.seenEvents.isEmpty());
    }

    @Test
    void rejectsAnEventFromSomeoneWhoIsNotASessionMember() {
        resolveParticipant.failure = new NotSessionMemberException();

        assertThrows(NotSessionMemberException.class, () -> service.collect(command(AttentionState.GOOD, "event-1")));
        assertTrue(statePort.currentState.isEmpty());
    }

    @Test
    void rejectsAnEventThatArrivesAfterTheSessionEnded() {
        resolveParticipant.failure = new SessionAlreadyEndedException();

        assertThrows(
                SessionAlreadyEndedException.class, () -> service.collect(command(AttentionState.GOOD, "event-1")));
        assertTrue(statePort.currentState.isEmpty());
    }

    private CollectAttentionEventCommand command(AttentionState state, String clientEventId) {
        return new CollectAttentionEventCommand(
                SESSION_ID, STUDENT_USER, state, ENDED_AT.minusSeconds(10), ENDED_AT, 10, 0.92d, clientEventId);
    }

    private static final class StubResolveSessionParticipant implements ResolveSessionParticipantUseCase {

        private RuntimeException failure;
        private SessionParticipantRole role = SessionParticipantRole.STUDENT;

        @Override
        public ResolveSessionParticipantResult resolve(ResolveSessionParticipantQuery query) {
            if (failure != null) {
                throw failure;
            }
            return new ResolveSessionParticipantResult(STUDENT_PARTICIPANT, role, ENDED_AT.minusSeconds(600));
        }
    }

    private static final class InMemoryAttentionStatePort implements AttentionStatePort {

        private final Set<String> seenEvents = new HashSet<>();
        private final Map<Long, AttentionSnapshot> currentState = new HashMap<>();
        private final Set<AttentionState> significant = new HashSet<>();
        private final Set<Long> excluded = new HashSet<>();
        private Duration currentStateTtl;
        private Duration significantWindow;
        private boolean failCurrentStateWrite;
        private boolean failSignificantWrite;

        private static String eventKey(long sessionId, long participantId, String clientEventId) {
            return sessionId + ":" + participantId + ":" + clientEventId;
        }

        @Override
        public boolean registerEvent(long sessionId, long participantId, String clientEventId, Duration ttl) {
            return seenEvents.add(eventKey(sessionId, participantId, clientEventId));
        }

        @Override
        public void clearEvent(long sessionId, long participantId, String clientEventId) {
            seenEvents.remove(eventKey(sessionId, participantId, clientEventId));
        }

        @Override
        public void recordCurrentState(long sessionId, long participantId, AttentionSnapshot snapshot, Duration ttl) {
            if (failCurrentStateWrite) {
                throw new IllegalStateException("redis down");
            }
            currentState.put(participantId, snapshot);
            currentStateTtl = ttl;
        }

        @Override
        public void markSignificant(long sessionId, long participantId, AttentionState state, Duration window) {
            if (failSignificantWrite) {
                throw new IllegalStateException("redis down");
            }
            significant.add(state);
            significantWindow = window;
        }

        @Override
        public void excludeFromDenominator(long sessionId, long participantId, Duration ttl) {
            excluded.add(participantId);
        }

        @Override
        public void includeInDenominator(long sessionId, long participantId) {
            excluded.remove(participantId);
        }
    }
}
