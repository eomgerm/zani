package com.a105.zani.session.application.confirmconnection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticipantConnectionServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long OTHER_SESSION_ID = 200L;
    private static final long PARTICIPANT_ID = 7L;
    private static final Instant CONNECTED_AT = Instant.parse("2026-07-25T05:01:00Z");
    private static final Instant RECONNECTED_AT = Instant.parse("2026-07-25T05:40:00Z");

    private final Map<Long, SessionParticipant> participantsById = new HashMap<>();
    private final List<SessionParticipant> saved = new ArrayList<>();

    private ParticipantConnectionService service;

    @BeforeEach
    void setUp() {
        participantsById.put(
                PARTICIPANT_ID,
                SessionParticipant.join(PARTICIPANT_ID, SESSION_ID, 1L, SessionParticipantRole.STUDENT, Instant.EPOCH));
        service = new ParticipantConnectionService(new SessionParticipantRepository() {
            @Override
            public Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId) {
                return Optional.empty();
            }

            @Override
            public Optional<SessionParticipant> findById(Long id) {
                return Optional.ofNullable(participantsById.get(id));
            }

            @Override
            public List<SessionParticipant> findBySessionId(Long sessionId) {
                return List.of();
            }

            @Override
            public SessionParticipant save(SessionParticipant participant) {
                saved.add(participant);
                return participant;
            }
        });
    }

    private void confirm(long sessionId, long participantId, Instant connectedAt) {
        service.confirm(new ConfirmParticipantConnectionCommand(sessionId, participantId, connectedAt));
    }

    @Test
    void 연결이_확인되면_자격을_부여하고_저장한다() {
        confirm(SESSION_ID, PARTICIPANT_ID, CONNECTED_AT);

        assertEquals(CONNECTED_AT, participantsById.get(PARTICIPANT_ID).firstJoinedAt());
        assertEquals(1, saved.size());
    }

    @Test
    void 중복_통지는_시각을_바꾸지_않고_저장도_생략한다() {
        confirm(SESSION_ID, PARTICIPANT_ID, CONNECTED_AT);
        saved.clear();

        confirm(SESSION_ID, PARTICIPANT_ID, RECONNECTED_AT);

        assertEquals(CONNECTED_AT, participantsById.get(PARTICIPANT_ID).firstJoinedAt());
        assertTrue(saved.isEmpty());
    }

    @Test
    void 다른_세션의_통지는_자격을_주지_않는다() {
        // 세션 대조가 없으면 남의 수업 통지로 이 참가자에게 자격이 생긴다.
        confirm(OTHER_SESSION_ID, PARTICIPANT_ID, CONNECTED_AT);

        assertNull(participantsById.get(PARTICIPANT_ID).firstJoinedAt());
        assertTrue(saved.isEmpty());
    }

    @Test
    void 알_수_없는_참가자는_예외를_던지지_않는다() {
        // 호출자가 LiveKit webhook 이라 예외는 5xx 가 되고 무한 재전송을 부른다.
        confirm(SESSION_ID, 999L, CONNECTED_AT);

        assertTrue(saved.isEmpty());
    }
}
