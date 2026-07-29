package com.a105.zani.session.infrastructure.persistence.mapper;

import java.time.Instant;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.infrastructure.persistence.entity.SessionParticipantJpaEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionParticipantPersistenceMapperTest {

    private final SessionParticipantPersistenceMapper mapper = new SessionParticipantPersistenceMapper();

    @Test
    void preservesRoleAsEnumAcrossPersistenceBoundary() {
        Instant enrolledAt = Instant.parse("2026-07-23T00:00:00Z");
        SessionParticipant participant =
                SessionParticipant.enroll(1L, 2L, 3L, SessionParticipantRole.STUDENT, enrolledAt);

        SessionParticipantJpaEntity entity = mapper.toEntity(participant);
        SessionParticipant restored = mapper.toDomain(entity);

        assertEquals(SessionParticipantRole.STUDENT, entity.getRole());
        assertEquals(SessionParticipantRole.STUDENT, restored.role());
    }

    /** API 입장만으로는 출석이 아니다. 미디어 연결 전까지 first_joined_at 이 비어 있어야 집계 분모에서 빠진다. */
    @Test
    void keepsMediaJoinTimesEmptyUntilLiveKitConfirmsTheConnection() {
        Instant enrolledAt = Instant.parse("2026-07-23T00:00:00Z");
        SessionParticipant participant =
                SessionParticipant.enroll(1L, 2L, 3L, SessionParticipantRole.STUDENT, enrolledAt);

        SessionParticipantJpaEntity enrolled = mapper.toEntity(participant);

        assertNull(enrolled.getFirstJoinedAt());
        assertNull(enrolled.getLastJoinedAt());

        Instant joinedAt = enrolledAt.plusSeconds(30);
        participant.confirmMediaJoin(joinedAt);
        participant.recordMediaLeft(joinedAt.plusSeconds(60));
        SessionParticipant restored = mapper.toDomain(mapper.toEntity(participant));

        assertEquals(joinedAt, restored.firstJoinedAt());
        assertEquals(joinedAt, restored.lastJoinedAt());
        assertEquals(joinedAt.plusSeconds(60), restored.lastLeftAt());
    }

    @Test
    void storesRoleByEnumName() throws NoSuchFieldException {
        Enumerated enumerated =
                SessionParticipantJpaEntity.class.getDeclaredField("role").getAnnotation(Enumerated.class);

        assertEquals(EnumType.STRING, enumerated.value());
    }
}
