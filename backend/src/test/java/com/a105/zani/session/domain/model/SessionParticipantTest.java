package com.a105.zani.session.domain.model;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** API 입장과 실제 미디어 연결의 구분. 이 구분이 무너지면 초대 코드만 누르고 접속하지 않은 학생이 출석·집계 분모에 섞인다. */
class SessionParticipantTest {

    private static final Instant T0 = Instant.parse("2026-07-26T00:00:00Z");

    private static SessionParticipant enrolled() {
        return SessionParticipant.enroll(1L, 2L, 3L, SessionParticipantRole.STUDENT, T0);
    }

    @Test
    void enrollingDoesNotCountAsAttendance() {
        SessionParticipant participant = enrolled();

        assertFalse(participant.hasJoinedMedia());
        assertNull(participant.firstJoinedAt());
        assertNull(participant.lastJoinedAt());
        assertEquals(T0, participant.lastAccessedAt());
    }

    @Test
    void theFirstLiveKitConnectionConfirmsAttendance() {
        SessionParticipant participant = enrolled();
        Instant joinedAt = T0.plusSeconds(30);

        assertTrue(participant.confirmMediaJoin(joinedAt));

        assertTrue(participant.hasJoinedMedia());
        assertEquals(joinedAt, participant.firstJoinedAt());
        assertEquals(joinedAt, participant.lastJoinedAt());
    }

    /** 재접속이 최초 입장 시각을 밀면 접속 1분 판정이 매번 초기화된다. */
    @Test
    void reconnectingUpdatesTheLatestJoinButNeverTheFirstOne() {
        SessionParticipant participant = enrolled();
        Instant first = T0.plusSeconds(30);
        Instant again = T0.plusSeconds(600);

        participant.confirmMediaJoin(first);

        assertFalse(participant.confirmMediaJoin(again));
        assertEquals(first, participant.firstJoinedAt());
        assertEquals(again, participant.lastJoinedAt());
    }

    /** 강제 퇴장·이탈은 현재 연결만 끊는다. 재입장과 사후 자료 접근 자격은 남는다(가이드 §6). */
    @Test
    void leavingKeepsTheAttendanceAlreadyEarned() {
        SessionParticipant participant = enrolled();
        participant.confirmMediaJoin(T0.plusSeconds(30));

        participant.recordMediaLeft(T0.plusSeconds(90));

        assertTrue(participant.hasJoinedMedia());
        assertEquals(T0.plusSeconds(30), participant.firstJoinedAt());
        assertEquals(T0.plusSeconds(90), participant.lastLeftAt());
    }
}
