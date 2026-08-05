package com.a105.zani.session.domain.model;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 사후 자료 접근 자격 규칙(FRD ACCESS-002). */
class SessionParticipantTest {

    private static final Instant JOIN_API_CALL = Instant.parse("2026-07-25T05:00:00Z");
    private static final Instant FIRST_CONNECT = Instant.parse("2026-07-25T05:01:00Z");
    private static final Instant RECONNECT = Instant.parse("2026-07-25T05:40:00Z");

    private SessionParticipant joined() {
        return SessionParticipant.join(1L, 100L, 7L, SessionParticipantRole.STUDENT, JOIN_API_CALL);
    }

    @Test
    void 입장_API만_부른_참가자는_자격이_없다() {
        assertNull(joined().firstJoinedAt());
    }

    @Test
    void 최초_연결이_자격을_부여한다() {
        SessionParticipant participant = joined();

        assertTrue(participant.confirmConnection(FIRST_CONNECT));
        assertEquals(FIRST_CONNECT, participant.firstJoinedAt());
    }

    @Test
    void 재접속은_최초_연결_시각을_바꾸지_않는다() {
        SessionParticipant participant = joined();
        participant.confirmConnection(FIRST_CONNECT);

        assertFalse(participant.confirmConnection(RECONNECT));
        assertEquals(FIRST_CONNECT, participant.firstJoinedAt());
    }
}
