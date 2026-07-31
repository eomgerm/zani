package com.a105.zani.postclass.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Optional;
import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.postclass.infrastructure.persistence.entity.InstructorNoteJpaEntity;

public interface InstructorNoteJpaRepository extends JpaRepository<InstructorNoteJpaEntity, Long> {

    Optional<InstructorNoteJpaEntity> findBySessionId(Long sessionId);

    /**
     * DRAFT 인 메모의 본문과 마지막 입력 시각만 바꾼다. 바뀐 행 수가 0 이면 그 사이에 확정된 것이다.
     *
     * <p>엔티티를 통째로 저장하지 않는 이유: 읽어 둔 메모를 merge 로 쓰면 status·finalized_at 까지 함께 덮어쓴다. 읽은 뒤 다른 경로가 확정했다면 그 확정이 DRAFT 로
     * 되돌아가고, 사후 처리 작업은 이미 만들어진 채로 남는다. 조건을 UPDATE 안에 두면 확정된 행은 건드릴 수 없다.
     *
     * <p>updated_at 을 함께 쓰는 이유: 벌크 UPDATE 는 {@code @LastModifiedDate} 리스너를 타지 않아, 명시하지 않으면 수정 시각이 옛 값에 머문다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update InstructorNoteJpaEntity note
               set note.content = :content, note.lastEditedAt = :editedAt, note.updatedAt = :editedAt
             where note.sessionId = :sessionId and note.status = 'DRAFT'
            """)
    int updateDraft(
            @Param("sessionId") Long sessionId, @Param("content") String content, @Param("editedAt") Instant editedAt);

    /**
     * DRAFT 인 메모만 FINALIZED 로 바꾼다. 바뀐 행 수가 곧 "이번 호출이 확정을 일으켰는지"다.
     *
     * <p>읽고 나서 쓰지 않는 이유: 수동 완료와 자동 확정이 서로 다른 트랜잭션에서 같은 순간에 들어오면 둘 다 DRAFT 를 읽는다. 조건을 UPDATE 안에 두면 행 잠금이 순서를 정해 주고, 뒤에 온
     * 쪽은 0 행을 받는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update InstructorNoteJpaEntity note
               set note.status = 'FINALIZED', note.finalizedAt = :finalizedAt, note.updatedAt = :finalizedAt
             where note.sessionId = :sessionId and note.status = 'DRAFT'
            """)
    int finalizeIfDraft(@Param("sessionId") Long sessionId, @Param("finalizedAt") Instant finalizedAt);

    /**
     * 초안 없이 확정된 메모를 넣는다. 같은 세션의 행이 이미 있으면 아무것도 바꾸지 않는다.
     *
     * <p>INSERT IGNORE 를 쓰지 않는 이유: 이 테이블은 session_participants 로 FK 가 걸려 있어 IGNORE 가 FK 위반까지 경고로 삼켜 버린다. ON DUPLICATE
     * KEY UPDATE 는 중복 키만 흡수하고 FK 위반은 그대로 올린다. {@code id = id} 는 아무것도 바꾸지 않는 no-op 이다.
     *
     * @return 넣었으면 1, 이미 있었으면 0
     */
    @Modifying
    @Query(
            value = "INSERT INTO instructor_notes"
                    + " (id, session_id, instructor_participant_id, status, finalized_at, created_at, updated_at)"
                    + " VALUES (:id, :sessionId, :participantId, 'FINALIZED', :finalizedAt, :finalizedAt, :finalizedAt)"
                    + " ON DUPLICATE KEY UPDATE id = id",
            nativeQuery = true)
    int insertFinalizedIfAbsent(
            @Param("id") Long id,
            @Param("sessionId") Long sessionId,
            @Param("participantId") Long participantId,
            @Param("finalizedAt") Instant finalizedAt);

    /** 메모 ID 를 잠금 읽기로 가져온다. {@link #findFinalizedAtForUpdate} 와 같은 이유로 스냅숏 밖을 본다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select note.id from InstructorNoteJpaEntity note where note.sessionId = :sessionId")
    Optional<Long> findIdForUpdate(@Param("sessionId") Long sessionId);

    /**
     * 확정 시각을 잠금 읽기로 가져온다. 잠금 읽기는 스냅숏이 아니라 최신 커밋본을 보므로, 같은 트랜잭션에서 이미 한 번 읽은 뒤라도 다른 요청이 방금 커밋한 확정을 볼 수 있다.
     *
     * <p>확정 경합에서 진 경로에서만 부른다. 그 시점에는 이긴 쪽이 이미 커밋해 잠금을 놓았고, 이 트랜잭션도 곧 끝나므로 잠금이 오래 남지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select note.finalizedAt from InstructorNoteJpaEntity note where note.sessionId = :sessionId")
    Optional<Instant> findFinalizedAtForUpdate(@Param("sessionId") Long sessionId);
}
