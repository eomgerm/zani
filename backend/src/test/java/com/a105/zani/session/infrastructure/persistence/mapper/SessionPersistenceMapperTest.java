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
import static org.junit.jupiter.api.Assertions.assertNull;

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

    /**
     * 종료 시각이 저장 경계를 왕복해도 살아남는지.
     *
     * <p>이 필드는 {@code toEntity} 에서 빠져 있어 값이 조용히 사라졌고, 그 탓에 리포트가 수업 길이를 알 수 없었다. 매핑에서 한 번 더 빠지면 같은 일이 반복되므로 여기서 잡는다.
     */
    @Test
    void preservesTheEndTimeAcrossPersistenceBoundary() {
        Instant endedAt = Instant.parse("2026-07-23T01:30:00Z");
        Session session = Session.start(1L, 2L, "테스트 세션", "ABCDEFGH", Instant.parse("2026-07-23T00:00:00Z"));
        session.end(endedAt);

        SessionJpaEntity entity = mapper.toEntity(session);
        Session restored = mapper.toDomain(entity);

        assertEquals(endedAt, entity.getEndedAt());
        assertEquals(endedAt, restored.endedAt());
        assertEquals(SessionStatus.ENDED, restored.status());
    }

    @Test
    void leavesTheEndTimeEmptyForALiveSession() {
        Session session = Session.start(1L, 2L, "테스트 세션", "ABCDEFGH", Instant.parse("2026-07-23T00:00:00Z"));

        SessionJpaEntity entity = mapper.toEntity(session);

        assertNull(entity.getEndedAt());
        assertNull(mapper.toDomain(entity).endedAt());
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
