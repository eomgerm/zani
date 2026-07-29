package com.a105.zani.session.domain.model;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.a105.zani.session.domain.exception.IllegalSessionTransitionException;
import com.a105.zani.session.domain.exception.InvalidSessionTitleException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 세션 생명주기 전이 규칙. 전이가 멱등하지 않으면 재시도·중복 webhook·동시 종료 요청이 후속 작업(Egress 정리, 알림)을 두 번 일으키고, 뒤로 가는 전이를 허용하면 이미 정리된 수업이 다시
 * 살아난다.
 */
class SessionTest {

    private static final Instant T0 = Instant.parse("2026-07-26T00:00:00Z");

    private static Session prepared() {
        return Session.prepare(1L, 2L, "제목", "ABCDEFGH");
    }

    private static Session live() {
        Session session = prepared();
        session.markLive(T0);
        return session;
    }

    @Test
    void startsInPreparingWithoutAStartTimeOrExpiry() {
        Session session = prepared();

        assertEquals(SessionStatus.PREPARING, session.status());
        assertEquals(null, session.startedAt());
        assertTrue(session.expiresAt().isEmpty());
    }

    @Test
    void rejectsABlankOrOverlongTitle() {
        assertThrows(InvalidSessionTitleException.class, () -> Session.prepare(1L, 2L, "   ", "ABCDEFGH"));
        assertThrows(InvalidSessionTitleException.class, () -> Session.prepare(1L, 2L, "가".repeat(101), "ABCDEFGH"));
    }

    @Test
    void startsTheThreeHourClockOnlyWhenTheSessionActuallyGoesLive() {
        Session session = prepared();

        assertTrue(session.markLive(T0));

        assertEquals(SessionStatus.LIVE, session.status());
        assertEquals(T0, session.startedAt());
        assertEquals(T0.plus(Session.ACTIVE_DURATION), session.expiresAt().orElseThrow());
    }

    /** 시작을 두 번 눌러도 시작 시각이 뒤로 밀리면 안 된다. 최대 수업 시간이 그만큼 늘어난다. */
    @Test
    void startingAnAlreadyLiveSessionChangesNothing() {
        Session session = live();

        assertFalse(session.markLive(T0.plusSeconds(600)));
        assertEquals(T0, session.startedAt());
    }

    @Test
    void movesFromLiveThroughEndingIntoTheNoteWindow() {
        Session session = live();

        assertTrue(session.beginEnding(SessionEndReason.INSTRUCTOR_REQUEST, T0.plusSeconds(60)));
        assertEquals(SessionStatus.ENDING, session.status());
        assertEquals(SessionEndReason.INSTRUCTOR_REQUEST, session.endReason());
        assertEquals(T0.plusSeconds(60), session.endedAt());

        assertTrue(session.markNotePending(T0.plusSeconds(60)));
        assertEquals(SessionStatus.NOTE_PENDING, session.status());
        assertEquals(T0.plusSeconds(60).plus(Session.NOTE_WINDOW), session.noteDueAt());
        assertEquals(SessionAnalysisStatus.WAITING_FOR_NOTE, session.analysisStatus());
    }

    /** 시작하지 않은 세션도 곧바로 정리할 수 있어야 한다(방치된 준비 상태 세션). */
    @Test
    void canEndStraightFromPreparing() {
        Session session = prepared();

        assertTrue(session.beginEnding(SessionEndReason.ABANDONED_BEFORE_START, T0));
        assertEquals(SessionStatus.ENDING, session.status());
    }

    /** 두 번째 종료 요청이 사유를 덮어쓰면 "왜 끝났는지"가 뒤늦은 요청으로 바뀐다. */
    @ParameterizedTest
    @EnumSource(names = {"ENDING", "NOTE_PENDING", "ENDED"})
    void endingIsIdempotentAndKeepsTheOriginalReason(SessionStatus alreadyEnding) {
        Session session = Session.reconstitute(
                1L,
                2L,
                "제목",
                "ABCDEFGH",
                false,
                T0,
                alreadyEnding,
                SessionAnalysisStatus.NOT_STARTED,
                T0,
                null,
                SessionEndReason.MAX_DURATION_REACHED);

        assertFalse(session.beginEnding(SessionEndReason.INSTRUCTOR_REQUEST, T0.plusSeconds(600)));
        assertEquals(SessionEndReason.MAX_DURATION_REACHED, session.endReason());
        assertEquals(alreadyEnding, session.status());
        assertEquals(T0, session.endedAt());
    }

    @Test
    void refusesToGoLiveAgainAfterEndingHasStarted() {
        Session session = live();
        session.beginEnding(SessionEndReason.INSTRUCTOR_REQUEST, T0);

        assertThrows(IllegalSessionTransitionException.class, () -> session.markLive(T0.plusSeconds(60)));
    }

    @Test
    void refusesToEnterTheNoteWindowBeforeEndingHasStarted() {
        Session session = live();

        assertThrows(IllegalSessionTransitionException.class, () -> session.markNotePending(T0));
    }

    @Test
    void closesTheNoteWindowOnlyOnceTheDeadlineHasPassed() {
        Session session = live();
        session.beginEnding(SessionEndReason.INSTRUCTOR_REQUEST, T0);
        session.markNotePending(T0);
        Instant due = session.noteDueAt();

        assertFalse(session.closeNoteWindow(due.minusSeconds(1)));
        assertEquals(SessionStatus.NOTE_PENDING, session.status());

        assertTrue(session.closeNoteWindow(due));
        assertEquals(SessionStatus.ENDED, session.status());
        // 분석 진행 상태는 분석 파이프라인이 소유한다. 아무도 처리하지 않는 세션을 처리 중으로 보이게 하면 안 된다.
        assertEquals(SessionAnalysisStatus.WAITING_FOR_NOTE, session.analysisStatus());

        assertFalse(session.closeNoteWindow(due.plusSeconds(60)));
    }

    /** 초대 코드는 진행 중인 수업에만 통해야 한다. 준비 중인 방에 학생이 들어오면 강사 점검을 방해한다. */
    @Test
    void acceptsNewParticipantsOnlyWhileLive() {
        assertFalse(prepared().acceptsNewParticipants());
        assertTrue(live().acceptsNewParticipants());

        Session ending = live();
        ending.beginEnding(SessionEndReason.INSTRUCTOR_REQUEST, T0);
        assertFalse(ending.acceptsNewParticipants());
    }

    /** 강사는 준비 단계부터 토큰을 받아야 미디어를 붙여 수업을 시작할 수 있고, 학생은 시작 이후에만 받는다. */
    @Test
    void issuesTokensToTheInstructorEarlierThanToStudents() {
        Session preparing = prepared();

        assertTrue(preparing.canIssueTokenFor(SessionParticipantRole.INSTRUCTOR));
        assertFalse(preparing.canIssueTokenFor(SessionParticipantRole.STUDENT));

        Session started = live();

        assertTrue(started.canIssueTokenFor(SessionParticipantRole.INSTRUCTOR));
        assertTrue(started.canIssueTokenFor(SessionParticipantRole.STUDENT));
    }

    @Test
    void reportsEndingAsStartedFromTheMomentTheProcedureBegins() {
        Session session = live();
        assertFalse(session.hasStartedEnding());

        session.beginEnding(SessionEndReason.INSTRUCTOR_REQUEST, T0);
        assertTrue(session.hasStartedEnding());
        // 아직 최종 종료는 아니다. 강사 메모를 받을 시간이 남아 있다.
        assertFalse(session.isEnded());
    }
}
