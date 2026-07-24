package com.a105.zani.session.infrastructure.persistence.mapper;

import java.time.Instant;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionAnalysisStatus;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;
import com.a105.zani.session.infrastructure.persistence.entity.SessionStatusChangeJpaEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionPersistenceMapperTest {

    private final SessionPersistenceMapper mapper = new SessionPersistenceMapper();

    @Test
    void preservesSessionStatusAsEnumAcrossPersistenceBoundary() {
        Session session = Session.start(1L, 2L, "테스트 세션", "ABCDEFGH", Instant.parse("2026-07-23T00:00:00Z"));

        SessionJpaEntity entity = mapper.toEntity(session);
        Session restored = mapper.toDomain(entity);

        assertEquals(SessionStatus.LIVE, entity.getStatus());
        assertEquals(SessionStatus.LIVE, restored.status());
        assertEquals(SessionAnalysisStatus.NOT_STARTED, entity.getAnalysisStatus());
        assertEquals(SessionAnalysisStatus.NOT_STARTED, restored.analysisStatus());
    }

    @Test
    void storesSessionStatusByEnumName() throws NoSuchFieldException {
        Enumerated enumerated =
                SessionJpaEntity.class.getDeclaredField("status").getAnnotation(Enumerated.class);

        assertEquals(EnumType.STRING, enumerated.value());
    }

    @Test
    void storesSessionAnalysisStatusByEnumName() throws NoSuchFieldException {
        Enumerated enumerated =
                SessionJpaEntity.class.getDeclaredField("analysisStatus").getAnnotation(Enumerated.class);

        assertEquals(EnumType.STRING, enumerated.value());
    }

    @Test
    void storesSessionStatusHistoryByEnumName() throws NoSuchFieldException {
        Enumerated fromStatus = SessionStatusChangeJpaEntity.class
                .getDeclaredField("fromStatus")
                .getAnnotation(Enumerated.class);
        Enumerated toStatus =
                SessionStatusChangeJpaEntity.class.getDeclaredField("toStatus").getAnnotation(Enumerated.class);

        assertEquals(EnumType.STRING, fromStatus.value());
        assertEquals(EnumType.STRING, toStatus.value());
    }
}
