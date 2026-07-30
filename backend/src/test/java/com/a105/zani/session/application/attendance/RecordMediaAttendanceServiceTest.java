package com.a105.zani.session.application.attendance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.domain.model.ParticipantIdentity;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 출석은 "입장 API 를 눌렀다"가 아니라 "실제로 방에 들어왔다"로 판단해야 한다. 프리조인 화면만 보고 나간 학생을 출석으로 세지 않으려는 것이다. */
class RecordMediaAttendanceServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long PARTICIPANT_ID = 55L;
    private static final Instant API_JOINED_AT = Instant.parse("2026-07-30T09:00:00Z");
    private static final Instant MEDIA_JOINED_AT = Instant.parse("2026-07-30T09:05:00Z");

    private final InMemoryParticipantRepository participants = new InMemoryParticipantRepository();
    private final RecordMediaAttendanceService service = new RecordMediaAttendanceService(participants);

    @Test
    void recordsTheMomentTheParticipantActuallyEnteredTheRoom() {
        participants.add(participant());

        service.record(joinedAt(MEDIA_JOINED_AT));

        SessionParticipant saved = participants.byId(PARTICIPANT_ID);
        assertEquals(MEDIA_JOINED_AT, saved.mediaFirstJoinedAt());
        // 입장 API 시각은 그대로 남아야 한다. 두 사실을 구분하려고 컬럼을 나눴다.
        assertEquals(API_JOINED_AT, saved.firstJoinedAt());
    }

    /** webhook 은 재전송된다. 재전송마다 최초 입장 시각을 갱신하면 출석이 그때부터 시작된 것으로 남는다. */
    @Test
    void keepsTheFirstJoinTimeWhenTheSameEventArrivesAgain() {
        participants.add(participant());
        service.record(joinedAt(MEDIA_JOINED_AT));

        service.record(joinedAt(MEDIA_JOINED_AT.plusSeconds(600)));

        assertEquals(MEDIA_JOINED_AT, participants.byId(PARTICIPANT_ID).mediaFirstJoinedAt());
    }

    /** webhook 도착 순서는 보장되지 않는다. 재접속 이벤트가 최초 입장보다 먼저 처리되면, 나중에 도착한 더 이른 시각을 받아들여야 출석이 실제 입장 시점부터로 남는다. */
    @Test
    void acceptsAnEarlierJoinThatArrivesLate() {
        participants.add(participant());
        // 재접속(늦은 시각)이 먼저 처리된 상황
        service.record(joinedAt(MEDIA_JOINED_AT.plusSeconds(600)));

        service.record(joinedAt(MEDIA_JOINED_AT));

        assertEquals(MEDIA_JOINED_AT, participants.byId(PARTICIPANT_ID).mediaFirstJoinedAt());
    }

    /** 이탈도 같다. 순서가 뒤바뀐 이벤트가 최종 이탈 시각을 과거로 되돌리면 출석 구간이 실제보다 짧아진다. */
    @Test
    void ignoresALeaveThatIsOlderThanTheOneAlreadyRecorded() {
        participants.add(participant());
        Instant lastLeave = MEDIA_JOINED_AT.plusSeconds(1_800);
        service.record(leftAt(lastLeave));

        service.record(leftAt(MEDIA_JOINED_AT.plusSeconds(60)));

        assertEquals(lastLeave, participants.byId(PARTICIPANT_ID).mediaLastLeftAt());
    }

    /** 바뀐 게 없으면 저장하지 않는다. 재전송이 잦은 경로라 무의미한 쓰기를 남기지 않는다. */
    @Test
    void doesNotSaveWhenNothingChanged() {
        participants.add(participant());
        service.record(joinedAt(MEDIA_JOINED_AT));
        participants.saved.clear();

        service.record(joinedAt(MEDIA_JOINED_AT));

        assertTrue(participants.saved.isEmpty());
    }

    @Test
    void recordsTheLeaveTime() {
        participants.add(participant());
        Instant leftAt = MEDIA_JOINED_AT.plusSeconds(1_800);

        service.record(
                new RecordMediaAttendanceCommand(SESSION_ID, ParticipantIdentity.of(PARTICIPANT_ID), false, leftAt));

        assertEquals(leftAt, participants.byId(PARTICIPANT_ID).mediaLastLeftAt());
    }

    /** 재접속하면 마지막 이탈 시각을 지우지 않고 다음 이탈에 덮어쓴다 — 마지막으로 나간 시각이 출석의 끝이다. */
    @Test
    void overwritesTheLeaveTimeOnEachDisconnect() {
        participants.add(participant());
        Instant firstLeave = MEDIA_JOINED_AT.plusSeconds(60);
        Instant secondLeave = MEDIA_JOINED_AT.plusSeconds(600);

        service.record(new RecordMediaAttendanceCommand(
                SESSION_ID, ParticipantIdentity.of(PARTICIPANT_ID), false, firstLeave));
        service.record(joinedAt(MEDIA_JOINED_AT.plusSeconds(120)));
        service.record(new RecordMediaAttendanceCommand(
                SESSION_ID, ParticipantIdentity.of(PARTICIPANT_ID), false, secondLeave));

        assertEquals(secondLeave, participants.byId(PARTICIPANT_ID).mediaLastLeftAt());
    }

    /** Egress·시스템 참가자도 같은 방에 참가자로 들어온다. 그들은 인원과 출석에서 빠져야 하고, 예외를 던지면 webhook 이 5xx 를 받아 무한히 재전송된다. */
    @Test
    void ignoresIdentitiesThatAreNotSessionParticipants() {
        participants.add(participant());

        service.record(new RecordMediaAttendanceCommand(SESSION_ID, "EG_abcdef", true, MEDIA_JOINED_AT));

        assertTrue(participants.saved.isEmpty());
        assertNull(participants.byId(PARTICIPANT_ID).mediaFirstJoinedAt());
    }

    /** 같은 미디어 서버를 다른 환경과 공유하면 다른 세션의 참가자 ID 가 들어올 수 있다. */
    @Test
    void ignoresAParticipantThatBelongsToAnotherSession() {
        participants.add(participant());

        service.record(new RecordMediaAttendanceCommand(
                SESSION_ID + 1, ParticipantIdentity.of(PARTICIPANT_ID), true, MEDIA_JOINED_AT));

        assertTrue(participants.saved.isEmpty());
    }

    private static RecordMediaAttendanceCommand joinedAt(Instant occurredAt) {
        return new RecordMediaAttendanceCommand(SESSION_ID, ParticipantIdentity.of(PARTICIPANT_ID), true, occurredAt);
    }

    private static RecordMediaAttendanceCommand leftAt(Instant occurredAt) {
        return new RecordMediaAttendanceCommand(SESSION_ID, ParticipantIdentity.of(PARTICIPANT_ID), false, occurredAt);
    }

    private static SessionParticipant participant() {
        return SessionParticipant.join(PARTICIPANT_ID, SESSION_ID, 7L, SessionParticipantRole.STUDENT, API_JOINED_AT);
    }

    private static final class InMemoryParticipantRepository implements SessionParticipantRepository {

        private final List<SessionParticipant> store = new ArrayList<>();
        private final List<SessionParticipant> saved = new ArrayList<>();

        void add(SessionParticipant participant) {
            store.add(participant);
        }

        SessionParticipant byId(long id) {
            return store.stream()
                    .filter(participant -> participant.id() == id)
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId) {
            return Optional.empty();
        }

        @Override
        public Optional<SessionParticipant> findById(Long id) {
            return store.stream()
                    .filter(participant -> id.equals(participant.id()))
                    .findFirst();
        }

        @Override
        public List<SessionParticipant> findBySessionId(Long sessionId) {
            return store.stream()
                    .filter(participant -> sessionId.equals(participant.sessionId()))
                    .toList();
        }

        @Override
        public SessionParticipant save(SessionParticipant participant) {
            saved.add(participant);
            return participant;
        }
    }
}
