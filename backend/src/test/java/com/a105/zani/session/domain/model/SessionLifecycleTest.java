package com.a105.zani.session.domain.model;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.domain.exception.SessionNotLiveException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 확정 흐름 PREPARING → LIVE → ENDING → NOTE_PENDING 은 역행하지 않고, 같은 전이를 두 번 요청해도 값이 밀리지 않는다. */
class SessionLifecycleTest {

    private static final Instant STARTED_AT = Instant.parse("2026-07-30T09:00:00Z");
    private static final Instant ENDED_AT = Instant.parse("2026-07-30T10:00:00Z");

    @Test
    void aNewSessionIsPreparingAndHasNoStartOrExpiry() {
        Session session = prepared();

        assertEquals(SessionStatus.PREPARING, session.status());
        assertNull(session.startedAt());
        // 아직 시작하지 않았으므로 3시간 시계가 돌지 않는다.
        assertNull(session.expiresAt());
    }

    /** 만료는 실제 시작 기준이어야 한다. 생성 시각으로 재면 준비에 쓴 시간만큼 수업 시간이 줄어든다. */
    @Test
    void startingSetsTheStartTimeAndTheThreeHourExpiry() {
        Session session = prepared();

        assertTrue(session.start(STARTED_AT));

        assertEquals(SessionStatus.LIVE, session.status());
        assertEquals(STARTED_AT, session.startedAt());
        assertEquals(STARTED_AT.plus(Session.ACTIVE_DURATION), session.expiresAt());
    }

    /** 시작 버튼을 두 번 눌러도 만료가 밀리면 안 된다. */
    @Test
    void startingAgainDoesNotPushTheStartTime() {
        Session session = prepared();
        session.start(STARTED_AT);

        assertFalse(session.start(STARTED_AT.plusSeconds(600)));
        assertEquals(STARTED_AT, session.startedAt());
    }

    @Test
    void aSessionThatHasEnteredTeardownCannotBeStarted() {
        Session session = prepared();
        session.start(STARTED_AT);
        session.beginEnding(ENDED_AT, SessionEndReason.INSTRUCTOR_REQUEST);

        assertThrows(SessionNotLiveException.class, () -> session.start(ENDED_AT.plusSeconds(60)));
    }

    @Test
    void endingRecordsTheTimeAndReason() {
        Session session = prepared();
        session.start(STARTED_AT);

        assertTrue(session.beginEnding(ENDED_AT, SessionEndReason.MAX_DURATION_REACHED));

        assertEquals(SessionStatus.ENDING, session.status());
        assertEquals(ENDED_AT, session.endedAt());
        assertEquals(SessionEndReason.MAX_DURATION_REACHED, session.endReason());
    }

    /** 중복 종료 요청이 첫 종료의 시각·사유를 덮어쓰면 왜 끝났는지가 바뀐다. */
    @Test
    void endingAgainKeepsTheFirstTimeAndReason() {
        Session session = prepared();
        session.start(STARTED_AT);
        session.beginEnding(ENDED_AT, SessionEndReason.INSTRUCTOR_REQUEST);

        assertFalse(session.beginEnding(ENDED_AT.plusSeconds(60), SessionEndReason.INSTRUCTOR_ABSENT));

        assertEquals(ENDED_AT, session.endedAt());
        assertEquals(SessionEndReason.INSTRUCTOR_REQUEST, session.endReason());
    }

    /** 준비만 하고 접은 수업도 끝낼 수 있어야 한다. 그러지 않으면 활성 잠금이 3시간 TTL 동안 강사를 막는다. */
    @Test
    void aPreparingSessionCanBeEndedWithoutEverStarting() {
        Session session = prepared();

        assertTrue(session.beginEnding(ENDED_AT, SessionEndReason.INSTRUCTOR_REQUEST));

        assertEquals(SessionStatus.ENDING, session.status());
        assertNull(session.startedAt());
    }

    @Test
    void awaitingTheNoteOnlyFollowsTeardown() {
        Session session = prepared();
        session.start(STARTED_AT);

        // 정리 단계를 건너뛴 메모 대기 전이는 일어나지 않는다.
        assertFalse(session.awaitNote());
        assertEquals(SessionStatus.LIVE, session.status());

        session.beginEnding(ENDED_AT, SessionEndReason.INSTRUCTOR_REQUEST);
        assertTrue(session.awaitNote());
        assertEquals(SessionStatus.NOTE_PENDING, session.status());
        // 두 번째 호출은 아무 것도 바꾸지 않는다.
        assertFalse(session.awaitNote());
    }

    /** LIVE 와 ENDED 사이에 정리 단계가 생겨, "ENDED 가 아니다"로는 살아 있는지 판단할 수 없다. */
    @Test
    void onlyLiveIsLiveAndEveryTeardownStateIsClosed() {
        assertTrue(SessionStatus.LIVE.isLive());
        assertFalse(SessionStatus.PREPARING.isLive());

        assertFalse(SessionStatus.PREPARING.isClosed());
        assertFalse(SessionStatus.LIVE.isClosed());
        assertTrue(SessionStatus.ENDING.isClosed());
        assertTrue(SessionStatus.NOTE_PENDING.isClosed());
        assertTrue(SessionStatus.ENDED.isClosed());
    }

    private static Session prepared() {
        return Session.prepare(1L, 2L, "테스트 수업", "ABCDEFGH");
    }
}
