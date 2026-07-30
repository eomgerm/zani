package com.a105.zani.session.infrastructure.persistence.mapper;

import java.time.Instant;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionAnalysisStatus;
import com.a105.zani.session.domain.model.SessionEndReason;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;
import com.a105.zani.session.infrastructure.persistence.entity.SessionStatusChangeJpaEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionPersistenceMapperTest {

    private final SessionPersistenceMapper mapper = new SessionPersistenceMapper();

    @Test
    void preservesSessionStatusAsEnumAcrossPersistenceBoundary() {
        Session session = Session.prepare(1L, 2L, "테스트 세션", "ABCDEFGH");
        session.start(Instant.parse("2026-07-23T00:00:00Z"));

        SessionJpaEntity entity = mapper.toEntity(session);
        Session restored = mapper.toDomain(entity);

        assertEquals(SessionStatus.LIVE, entity.getStatus());
        assertEquals(SessionStatus.LIVE, restored.status());
        assertEquals(SessionAnalysisStatus.NOT_STARTED, entity.getAnalysisStatus());
        assertEquals(SessionAnalysisStatus.NOT_STARTED, restored.analysisStatus());
    }

    /** 준비 중인 세션은 시작 시각이 없다. 이 왕복에서 값이 생기면 3시간 만료가 생성 시각 기준으로 계산된다. */
    @Test
    void keepsAPreparingSessionWithoutAStartTime() {
        Session session = Session.prepare(1L, 2L, "테스트 세션", "ABCDEFGH");

        Session restored = mapper.toDomain(mapper.toEntity(session));

        assertEquals(SessionStatus.PREPARING, restored.status());
        assertNull(restored.startedAt());
        assertNull(restored.expiresAt());
    }

    /** 종료 사유는 로그가 아니라 행에 남아야 왕복 후에도 남는다. */
    @Test
    void carriesTheEndReasonAndTimeAcrossPersistenceBoundary() {
        Session session = Session.prepare(1L, 2L, "테스트 세션", "ABCDEFGH");
        session.start(Instant.parse("2026-07-23T00:00:00Z"));
        Instant endedAt = Instant.parse("2026-07-23T01:00:00Z");
        session.beginEnding(endedAt, SessionEndReason.INSTRUCTOR_REQUEST);

        Session restored = mapper.toDomain(mapper.toEntity(session));

        assertEquals(SessionEndReason.INSTRUCTOR_REQUEST, restored.endReason());
        assertEquals(endedAt, restored.endedAt());
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
