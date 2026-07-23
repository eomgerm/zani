package com.a105.zani.session.infrastructure.persistence.mapper;

import java.time.Instant;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.infrastructure.persistence.entity.SessionParticipantJpaEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionParticipantPersistenceMapperTest {

    private final SessionParticipantPersistenceMapper mapper = new SessionParticipantPersistenceMapper();

    @Test
    void preservesRoleAsEnumAcrossPersistenceBoundary() {
        Instant joinedAt = Instant.parse("2026-07-23T00:00:00Z");
        SessionParticipant participant = SessionParticipant.join(1L, 2L, 3L, SessionParticipantRole.STUDENT, joinedAt);

        SessionParticipantJpaEntity entity = mapper.toEntity(participant);
        SessionParticipant restored = mapper.toDomain(entity);

        assertEquals(SessionParticipantRole.STUDENT, entity.getRole());
        assertEquals(SessionParticipantRole.STUDENT, restored.role());
    }

    @Test
    void storesRoleByEnumName() throws NoSuchFieldException {
        Enumerated enumerated =
                SessionParticipantJpaEntity.class.getDeclaredField("role").getAnnotation(Enumerated.class);

        assertEquals(EnumType.STRING, enumerated.value());
    }
}
