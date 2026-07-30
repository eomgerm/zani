package com.a105.zani.postclass.infrastructure.persistence.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.postclass.infrastructure.persistence.entity.PipelineJobJpaEntity;

/** pipeline_jobs 등록 쿼리. 단계 문자열은 {@link PipelineJobJpaEntity} 의 STATUS_* 상수만 쓰고 쿼리에는 리터럴을 두지 않는다. */
public interface PipelineJobJpaRepository extends JpaRepository<PipelineJobJpaEntity, Long> {

    /**
     * session_id UNIQUE 충돌을 호출자 트랜잭션 오염 없이 처리하기 위해 INSERT IGNORE 를 쓴다. 이 등록은 메모 확정과 같은 트랜잭션에서 일어나므로, JPA save 의 flush
     * 예외로 트랜잭션이 rollback-only 가 되면 "이미 있으면 조용히 넘어간다"는 계약 때문에 확정까지 되돌아간다.
     *
     * @return 삽입된 행 수(이미 있으면 0)
     */
    @Modifying
    @Query(
            value = "INSERT IGNORE INTO pipeline_jobs (id, session_id, status, created_at, updated_at)"
                    + " VALUES (:id, :sessionId, :status, :queuedAt, :queuedAt)",
            nativeQuery = true)
    // 시각은 DB 함수(NOW) 대신 바인딩으로 넘긴다: 확정 시각과 같은 값이어야 두 기록의 순서를 나중에 따질 수 있다.
    int insertIgnoreWithStatus(
            @Param("id") Long id,
            @Param("sessionId") Long sessionId,
            @Param("status") String status,
            @Param("queuedAt") Instant queuedAt);

    /** 새 작업은 항상 대기 상태로 등록한다. */
    default int insertIgnore(Long id, Long sessionId, Instant queuedAt) {
        return insertIgnoreWithStatus(id, sessionId, PipelineJobJpaEntity.STATUS_QUEUED, queuedAt);
    }
}
