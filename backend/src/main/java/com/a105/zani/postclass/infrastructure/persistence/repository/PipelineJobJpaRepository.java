package com.a105.zani.postclass.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.postclass.domain.model.PipelineStateMachine;
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
     * 여러 세션의 단계를 한 번에 읽는다. 목록 화면이 세션마다 물으면 목록 길이만큼 쿼리가 나간다.
     *
     * <p>잠금 없이 읽는다 — 이 값은 화면에 보여줄 뿐 전이 판단에 쓰지 않는다. 작업이 없는 세션은 결과에서 빠진다.
     */
    @Query("""
            select job.sessionId, job.status
              from PipelineJobJpaEntity job
             where job.sessionId in :sessionIds
            """)
    List<Object[]> findStatusesBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    /**
     * 단계·시도 횟수·등록 시각을 잠금 읽기로 가져온다. 잠금 읽기는 스냅숏이 아니라 최신 커밋본을 보므로, 앞선 전이가 방금 커밋한 단계까지 보인다.
     *
     * <p>전이·재시도 직전에만 부른다 — 여기서 잡은 잠금이 같은 트랜잭션의 UPDATE 까지 이어져야 두 워커가 같은 단계를 두 번 수행하지 않는다.
     *
     * <p>스칼라 대신 행 전체를 읽는 이유: 재시도 판단에 단계·시도 횟수·등록 시각이 모두 필요한데, 컬럼이 몇 개 되지 않아 나눠 읽어 잠금을 두 번 잡을 이유가 없다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from PipelineJobJpaEntity job where job.sessionId = :sessionId")
    Optional<PipelineJobJpaEntity> findForUpdate(@Param("sessionId") Long sessionId);

    /**
     * 잠금 없이 읽는다. 전이 판단에는 쓰지 않는다.
     *
     * <p>사후 전사가 8시간 마감의 기준점({@code created_at})을 얻는 데 쓴다. 수십 분 걸리는 작업이 {@link #findForUpdate} 의 잠금을 붙잡으면 SLA 경보와 다른 단계의
     * 전이가 잠금 대기로 실패한다.
     */
    Optional<PipelineJobJpaEntity> findBySessionId(Long sessionId);

    /**
     * 단계를 바꾸고 재시도 예산을 초기화한다. 갈 수 있는 단계인지는 잠금 읽기 뒤 호출자가 이미 판단했으므로 조건을 두지 않는다.
     *
     * <p>last_error 는 지우지 않는다. 그 값은 "마지막 실패 사유" 이력이라 단계가 넘어갔다고 사라질 이유가 없고, 무엇보다 FAILED 로 옮기는 것도 이 쿼리다 — 여기서 지우면 실패 사유를
     * 남기는 호출과 단계를 옮기는 호출의 순서에 따라 사유가 지워진다.
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
               set job.status = :status, job.updatedAt = :changedAt,
                   job.attemptCount = 0, job.nextAttemptAt = null
             where job.sessionId = :sessionId
            """)
    int updateStatus(
            @Param("sessionId") Long sessionId, @Param("status") String status, @Param("changedAt") Instant changedAt);

    /** 단계는 그대로 두고 시도 횟수를 올리며 다음 시도 시각과 실패 사유를 남긴다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PipelineJobJpaEntity job
               set job.attemptCount = job.attemptCount + 1, job.nextAttemptAt = :nextAttemptAt,
                   job.lastError = :error, job.updatedAt = :changedAt
             where job.sessionId = :sessionId
            """)
    int markRetry(
            @Param("sessionId") Long sessionId,
            @Param("error") String error,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("changedAt") Instant changedAt);

    /** 실패 사유를 남기고 재시도 대기를 푼다. 단계 전이는 호출자가 상태 머신을 거쳐 따로 한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PipelineJobJpaEntity job
               set job.lastError = :error, job.nextAttemptAt = null, job.updatedAt = :changedAt
             where job.sessionId = :sessionId
            """)
    int markFailed(
            @Param("sessionId") Long sessionId, @Param("error") String error, @Param("changedAt") Instant changedAt);

    /** 현재 단계를 유지한 채 재시도 대기만 푼다. 시도 횟수는 건드리지 않는다 — 재시도 선점 전용(S15P11A105-247). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PipelineJobJpaEntity job
               set job.nextAttemptAt = null, job.updatedAt = :changedAt
             where job.sessionId = :sessionId
            """)
    int clearRetryWait(@Param("sessionId") Long sessionId, @Param("changedAt") Instant changedAt);

    /**
     * 전사를 시작하거나 이어갈 수 있는 세션 ID. 오래 등록된 것부터.
     *
     * <p>{@code QUEUED} 전체와, 재시도 기한이 지난 {@code TRANSCRIBING} 만 고른다. {@code next_attempt_at} 이 {@code null} 인
     * {@code TRANSCRIBING} 은 <b>실행 중</b>이므로 빼야 한다 — 넣으면 진행 중인 세션이 매 주기마다 다시 발견된다.
     *
     * <p>단계 리터럴을 파라미터로 받는 이유는 {@link #findOverdueSessionIds} 와 같다. 단계 이름을 바꿀 때 쿼리 문자열을 놓치지 않도록 호출부가 enum 에서 넘긴다.
     */
    @Query("""
            select job.sessionId from PipelineJobJpaEntity job
             where job.status = :queuedStatus
                or (job.status = :transcribingStatus
                    and job.nextAttemptAt is not null and job.nextAttemptAt <= :now)
             order by job.createdAt asc
            """)
    List<Long> findDueTranscriptionSessionIds(
            @Param("queuedStatus") String queuedStatus,
            @Param("transcribingStatus") String transcribingStatus,
            @Param("now") Instant now,
            Pageable pageable);

    default List<Long> findDueTranscriptionSessionIds(Instant now, int limit) {
        return findDueTranscriptionSessionIds(
                PipelineStatus.QUEUED.name(), PipelineStatus.TRANSCRIBING.name(), now, Pageable.ofSize(limit));
    }

    /**
     * 아직 끝나지 않았는데 기준 시각보다 먼저 등록된 작업의 세션 ID. 오래 밀린 것부터.
     *
     * <p>미완료 단계를 <b>나열해서</b> 지목하는 이유: {@code status not in ('PUBLISHED','FAILED')} 로 뒤집어 쓰면 인덱스 선행 컬럼의 부정 조건이 되어 옵티마이저가
     * 훑는 범위가 넓어진다. 실측(2만 행, 완료 95%)에서 뒤집은 쪽은 601 행을 훑어 33% 만 남았고, 나열한 쪽은 589 행이 모두 조건을 통과했다. 목록은
     * {@link PipelineStateMachine#unfinishedStages()} 가 주므로 쿼리에 단계 리터럴은 두지 않는다.
     */
    @Query("""
            select job.sessionId from PipelineJobJpaEntity job
             where job.status in :unfinishedStatuses and job.createdAt <= :queuedBefore
             order by job.createdAt asc
            """)
    List<Long> findOverdueSessionIds(
            @Param("unfinishedStatuses") Collection<String> unfinishedStatuses,
            @Param("queuedBefore") Instant queuedBefore,
            Pageable pageable);

    /** 마감을 넘긴 작업. 끝난 단계(PUBLISHED·FAILED)는 더 볼 것이 없으므로 애초에 고르지 않는다. */
    default List<Long> findOverdueSessionIds(Instant queuedBefore, int limit) {
        return findOverdueSessionIds(
                PipelineStateMachine.unfinishedStages().stream()
                        .map(PipelineStatus::name)
                        .toList(),
                queuedBefore,
                Pageable.ofSize(limit));
    }
}
