package com.a105.zani.attention.application.getdenominator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.application.port.AttentionSnapshot;
import com.a105.zani.attention.application.port.AttentionStatePort;
import com.a105.zani.attention.application.port.ObservationApplied;
import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.DetectionRunTransition;
import com.a105.zani.session.application.resolveconnectedstudents.ConnectedStudent;
import com.a105.zani.session.application.resolveconnectedstudents.ResolveConnectedStudentsQuery;
import com.a105.zani.session.application.resolveconnectedstudents.ResolveConnectedStudentsResult;
import com.a105.zani.session.application.resolveconnectedstudents.ResolveConnectedStudentsUseCase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 확정 문서 §7·§7.1 의 분모 기준을 고정한다. 연속 접속 1분과 측정 불가 1분 제외가 각각 경계에서 갈린다. */
class GetDenominatorServiceTest {

    private static final long SESSION_ID = 100L;
    private static final Instant NOW = Instant.parse("2026-07-29T09:10:00Z");

    private final StubConnectedStudents connectedStudents = new StubConnectedStudents();
    private final InMemoryAttentionStatePort statePort = new InMemoryAttentionStatePort();

    private GetDenominatorService service;

    @BeforeEach
    void setUp() {
        service = new GetDenominatorService(connectedStudents, statePort, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void leavesOutAStudentWhoHasNotBeenConnectedForAFullMinute() {
        connectedStudents.add(1L, NOW.minusSeconds(59));

        GetDenominatorResult result = service.get(new GetDenominatorQuery(SESSION_ID));

        // 방금 들어온 학생을 바로 세면 아직 수업을 듣지도 않은 사람이 비율을 흔든다.
        assertTrue(result.denominator().isEmpty());
        assertEquals(1, result.connectedStudents());
        assertEquals(1, result.excludedStudents());
    }

    @Test
    void countsAStudentTheMomentTheConnectionReachesAMinute() {
        connectedStudents.add(1L, NOW.minusSeconds(60));

        GetDenominatorResult result = service.get(new GetDenominatorQuery(SESSION_ID));

        assertEquals(1, result.denominator().size());
        assertTrue(result.denominator().counts(1L));
    }

    @Test
    void leavesOutAStudentWhoseMeasurementHasBeenSuspendedForAMinute() {
        connectedStudents.add(1L, NOW.minusSeconds(300));
        connectedStudents.add(2L, NOW.minusSeconds(300));
        statePort.excluded.add(2L);

        GetDenominatorResult result = service.get(new GetDenominatorQuery(SESSION_ID));

        // 카메라를 켤 수 없는 학생을 분모에 남기면, 무엇을 하든 비율이 낮아져 어려움을 겪는 학생들이 가려진다.
        assertEquals(Set.of(1L), result.denominator().participantIds());
        assertEquals(2, result.connectedStudents());
        assertEquals(1, result.excludedStudents());
    }

    @Test
    void bringsAStudentBackAsSoonAsTheExclusionIsLifted() {
        connectedStudents.add(1L, NOW.minusSeconds(300));
        statePort.excluded.add(1L);
        assertTrue(
                service.get(new GetDenominatorQuery(SESSION_ID)).denominator().isEmpty());

        statePort.excluded.remove(1L);

        // 빼는 데 1분이 걸리고 넣는 데는 즉시인 비대칭이다(§7.1). 복귀는 판정 수집 쪽이 표시를 지우는 순간 끝난다.
        assertTrue(
                service.get(new GetDenominatorQuery(SESSION_ID)).denominator().counts(1L));
    }

    @Test
    void keepsAnUnmeasurableStudentInTheDenominator() {
        // UNMEASURABLE 은 카메라가 켜져 있고 관측 결과가 "얼굴이 없다"인 것이라 분모에 남고 분자에도 든다(§7.2).
        connectedStudents.add(1L, NOW.minusSeconds(300));

        assertTrue(
                service.get(new GetDenominatorQuery(SESSION_ID)).denominator().counts(1L));
    }

    @Test
    void reportsAnEmptyDenominatorWhenNobodyIsConnected() {
        GetDenominatorResult result = service.get(new GetDenominatorQuery(SESSION_ID));

        // 분모가 0이면 비율을 계산할 수 없다. 트리거는 판단을 미룬다(§7).
        assertTrue(result.denominator().isEmpty());
        assertEquals(0, result.connectedStudents());
        assertEquals(0, result.excludedStudents());
    }

    @Test
    void asksTheStoreOnlyAboutStudentsThatAlreadyPassedTheConnectionRule() {
        connectedStudents.add(1L, NOW.minusSeconds(300));
        connectedStudents.add(2L, NOW.minusSeconds(10));

        service.get(new GetDenominatorQuery(SESSION_ID));

        // 아직 세지 않는 학생까지 물으면 키 조회가 학생 수만큼 늘어난다. 후보를 좁혀서 넘긴다.
        assertEquals(List.of(Set.of(1L)), statePort.askedAbout);
    }

    @Test
    void countsOnlyTheStudentsThePresenceBoundaryReported() {
        // 강사는 애초에 목록에 없다(§2). 접속이 끊긴 학생도 presence 키가 사라져 여기 오지 않는다.
        connectedStudents.add(1L, NOW.minusSeconds(300));
        connectedStudents.add(2L, NOW.minusSeconds(300));

        GetDenominatorResult result = service.get(new GetDenominatorQuery(SESSION_ID));

        assertEquals(2, result.denominator().size());
        assertEquals(SESSION_ID, connectedStudents.askedSessionId);
    }

    private static final class StubConnectedStudents implements ResolveConnectedStudentsUseCase {

        private final List<ConnectedStudent> students = new ArrayList<>();
        private Long askedSessionId;

        private void add(long participantId, Instant connectedSince) {
            students.add(new ConnectedStudent(participantId, connectedSince));
        }

        @Override
        public ResolveConnectedStudentsResult resolve(ResolveConnectedStudentsQuery query) {
            askedSessionId = query.sessionId();
            return new ResolveConnectedStudentsResult(students);
        }
    }

    private static final class InMemoryAttentionStatePort implements AttentionStatePort {

        private final Set<Long> excluded = new HashSet<>();
        private final List<Set<Long>> askedAbout = new ArrayList<>();

        @Override
        public Set<Long> excludedFromDenominator(long sessionId, Collection<Long> participantIds) {
            askedAbout.add(Set.copyOf(participantIds));
            return participantIds.stream().filter(excluded::contains).collect(Collectors.toSet());
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
        public void markSignificant(long sessionId, long participantId, AttentionState state, Duration window) {}

        @Override
        public void excludeFromDenominator(long sessionId, long participantId, Duration ttl) {
            excluded.add(participantId);
        }

        @Override
        public void includeInDenominator(long sessionId, long participantId) {
            excluded.remove(participantId);
        }

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
