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

    private static final Instant STARTED_AT = Instant.parse("2026-07-23T00:00:00Z");
    private static final Instant ENDED_AT = Instant.parse("2026-07-23T01:00:00Z");

    private final SessionPersistenceMapper mapper = new SessionPersistenceMapper();

    @Test
    void preservesSessionStatusAsEnumAcrossPersistenceBoundary() {
        Session session = Session.prepare(1L, 2L, "테스트 세션", "ABCDEFGH");

        SessionJpaEntity entity = mapper.toEntity(session);
        Session restored = mapper.toDomain(entity);

        assertEquals(SessionStatus.PREPARING, entity.getStatus());
        assertEquals(SessionStatus.PREPARING, restored.status());
        assertEquals(SessionAnalysisStatus.NOT_STARTED, entity.getAnalysisStatus());
        assertEquals(SessionAnalysisStatus.NOT_STARTED, restored.analysisStatus());
    }

    @Test
    void leavesStartedAtEmptyUntilTheSessionActuallyStarts() {
        Session prepared = Session.prepare(1L, 2L, "테스트 세션", "ABCDEFGH");

        assertNull(mapper.toEntity(prepared).getStartedAt());

        prepared.markLive(STARTED_AT);

        assertEquals(STARTED_AT, mapper.toEntity(prepared).getStartedAt());
    }

    /**
     * 저장은 ID가 지정된 엔티티의 merge 라서, 매퍼가 싣지 않은 updatable 컬럼은 저장할 때마다 NULL 로 덮인다. 종료 시각·사유와 메모 마감은 종료 이후에도 계속 세션을 저장하는 경로(분석
     * 상태 갱신 등)가 있으므로 왕복이 깨지면 조용히 사라진다.
     */
    @Test
    void carriesEndStateThroughThePersistenceBoundary() {
        Session session = Session.prepare(1L, 2L, "테스트 세션", "ABCDEFGH");
        session.markLive(STARTED_AT);
        session.beginEnding(SessionEndReason.INSTRUCTOR_REQUEST, ENDED_AT);
        session.markNotePending(ENDED_AT);

        Session restored = mapper.toDomain(mapper.toEntity(session));

        assertEquals(SessionStatus.NOTE_PENDING, restored.status());
        assertEquals(ENDED_AT, restored.endedAt());
        assertEquals(SessionEndReason.INSTRUCTOR_REQUEST, restored.endReason());
        assertEquals(ENDED_AT.plus(Session.NOTE_WINDOW), restored.noteDueAt());
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
