package com.a105.zani.postclass.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Optional;
import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.postclass.domain.model.PipelineStatus;
import com.a105.zani.postclass.infrastructure.persistence.entity.PipelineJobJpaEntity;

/** pipeline_jobs 등록·단계 전이 쿼리. 단계 문자열은 {@link PipelineStatus} 만을 단일 소스로 쓰고 쿼리에는 리터럴을 두지 않는다. */
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
        return insertIgnoreWithStatus(id, sessionId, PipelineStatus.QUEUED.name(), queuedAt);
    }

    /**
     * 단계를 잠금 읽기로 가져온다. 잠금 읽기는 스냅숏이 아니라 최신 커밋본을 보므로, 앞선 전이가 방금 커밋한 단계까지 보인다.
     *
     * <p>전이 직전에만 부른다 — 여기서 잡은 잠금이 같은 트랜잭션의 UPDATE 까지 이어져야 두 워커가 같은 단계를 두 번 수행하지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job.status from PipelineJobJpaEntity job where job.sessionId = :sessionId")
    Optional<String> findStatusForUpdate(@Param("sessionId") Long sessionId);

    /**
     * 단계를 바꾼다. 갈 수 있는 단계인지는 잠금 읽기 뒤 호출자가 이미 판단했으므로 조건을 두지 않는다.
     *
     * <p>updated_at 을 함께 쓰는 이유: 벌크 UPDATE 는 {@code @LastModifiedDate} 리스너를 타지 않아, 명시하지 않으면 단계 변경 시각이 등록 시각에 머문다. 8시간
     * SLA(AI-006)를 재는 쪽이 이 값으로 어느 단계에서 멈췄는지 본다.
     *
     * <p>지금은 잠근 행을 조건 없이 바꾸므로 반환 행 수가 항상 1 이고, 호출자도 검사하지 않는다. <b>여기에 조건절을 붙인다면(예: {@code and status = :expected}) 호출자가
     * 0 행을 반드시 확인해야 한다</b> — 그러지 않으면 전이가 일어나지 않은 요청이 조용히 성공으로 처리된다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PipelineJobJpaEntity job
               set job.status = :status, job.updatedAt = :changedAt
             where job.sessionId = :sessionId
            """)
    int updateStatus(
            @Param("sessionId") Long sessionId, @Param("status") String status, @Param("changedAt") Instant changedAt);
}
