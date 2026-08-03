package com.a105.zani.session.infrastructure.persistence.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.session.infrastructure.persistence.entity.SessionParticipantJpaEntity;

public interface SessionParticipantJpaRepository extends JpaRepository<SessionParticipantJpaEntity, Long> {

    Optional<SessionParticipantJpaEntity> findBySessionIdAndMemberId(Long sessionId, Long memberId);

    List<SessionParticipantJpaEntity> findByMemberId(Long memberId);

    List<SessionParticipantJpaEntity> findBySessionIdOrderByIdAsc(Long sessionId);

    /**
     * 여러 세션의 참가자 수를 한 번에 센다.
     *
     * <p>목록은 세션을 여러 개 담으므로 세션마다 세면 그 수만큼 쿼리가 나간다(N+1). 참가자가 하나도 없는 세션은 결과에 아예 빠지므로, 부르는 쪽이 0 으로 채워야 한다.
     */
    @Query("""
            SELECT p.sessionId, COUNT(p)
            FROM SessionParticipantJpaEntity p
            WHERE p.sessionId IN :sessionIds
            GROUP BY p.sessionId
            """)
    List<Object[]> countGroupedBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);
}
