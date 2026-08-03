package com.a105.zani.attention.infrastructure.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.attention.infrastructure.persistence.entity.AttentionEventJpaEntity;
import com.a105.zani.attention.infrastructure.persistence.query.ObservationRow;
import com.a105.zani.attention.infrastructure.persistence.query.PromptRow;

/**
 * 리포트 타임라인이 재생할 원본 이력을 읽는다.
 *
 * <p>{@code AttentionEventJpaEntity} 를 통째로 불러오지 않고 필요한 컬럼만 투영한다. 30명 3시간이면 32,000 행이라 참가자 연관까지 딸려 오면 영속성 컨텍스트가 그만큼 부푼다.
 *
 * <p>관측은 시각을 둘 다 읽는다. {@code occurred_offset_ms} 는 값이 정해진 시각이고 판정이 덮은 10초 창의 시작은 {@code window_started_offset_ms} 이므로,
 * 뒤쪽이 없으면 타임라인의 시간축을 세울 수 없다. 정렬은 관측 시각으로 두고 창 시작 기준 재정렬은 재생기가 한다 — 창 시작이 빈 행의 보정 규칙이 도메인에 있어 SQL 로는 같은 순서를 만들 수 없다.
 */
public interface AttentionTimelineJpaRepository extends JpaRepository<AttentionEventJpaEntity, Long> {

    @Query("""
            select new com.a105.zani.attention.infrastructure.persistence.query.ObservationRow(
                e.sessionParticipantId, e.occurredOffsetMs, e.windowStartedOffsetMs, e.detectorOutcome)
            from AttentionEventJpaEntity e
            where e.sessionId = :sessionId and e.detectorOutcome is not null
            order by e.occurredOffsetMs asc
            """)
    List<ObservationRow> findObservations(@Param("sessionId") long sessionId);

    @Query("""
            select new com.a105.zani.attention.infrastructure.persistence.query.ObservationRow(
                e.sessionParticipantId, e.occurredOffsetMs, e.windowStartedOffsetMs, e.detectorOutcome)
            from AttentionEventJpaEntity e
            where e.sessionId = :sessionId
              and e.sessionParticipantId = :participantId
              and e.detectorOutcome is not null
            order by e.occurredOffsetMs asc
            """)
    List<ObservationRow> findObservations(
            @Param("sessionId") long sessionId, @Param("participantId") long participantId);

    /**
     * 이해 확인 프롬프트의 응답만 읽는다.
     *
     * <p>자세 안내·카메라 안내는 참여 상태를 만들지 않으므로 {@code trigger_type} 으로 걸러낸다(확정 문서 §5.2). 기준 시각은 응답 시각이고, 브라우저가 무응답으로 닫으며 보낸 행처럼
     * 응답 시각이 없으면 표시 시각으로 대신한다.
     */
    @Query("""
            select new com.a105.zani.attention.infrastructure.persistence.query.PromptRow(
                p.sessionParticipantId, coalesce(p.respondedOffsetMs, p.shownOffsetMs), p.response)
            from CheckPromptJpaEntity p
            where p.sessionId = :sessionId
              and p.triggerType = 'UNDERSTANDING_CHECK'
              and p.response is not null
            order by coalesce(p.respondedOffsetMs, p.shownOffsetMs) asc
            """)
    List<PromptRow> findPrompts(@Param("sessionId") long sessionId);

    @Query("""
            select new com.a105.zani.attention.infrastructure.persistence.query.PromptRow(
                p.sessionParticipantId, coalesce(p.respondedOffsetMs, p.shownOffsetMs), p.response)
            from CheckPromptJpaEntity p
            where p.sessionId = :sessionId
              and p.sessionParticipantId = :participantId
              and p.triggerType = 'UNDERSTANDING_CHECK'
              and p.response is not null
            order by coalesce(p.respondedOffsetMs, p.shownOffsetMs) asc
            """)
    List<PromptRow> findPrompts(@Param("sessionId") long sessionId, @Param("participantId") long participantId);
}
