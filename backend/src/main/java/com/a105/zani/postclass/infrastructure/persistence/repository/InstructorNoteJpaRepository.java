package com.a105.zani.postclass.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.postclass.infrastructure.persistence.entity.InstructorNoteJpaEntity;

public interface InstructorNoteJpaRepository extends JpaRepository<InstructorNoteJpaEntity, Long> {

    Optional<InstructorNoteJpaEntity> findBySessionId(Long sessionId);

    /**
     * DRAFT 인 메모만 FINALIZED 로 바꾼다. 바뀐 행 수가 곧 "이번 호출이 확정을 일으켰는지"다.
     *
     * <p>읽고 나서 쓰지 않는 이유: 수동 완료와 자동 확정이 서로 다른 트랜잭션에서 같은 순간에 들어오면 둘 다 DRAFT 를 읽는다. 조건을 UPDATE 안에 두면 행 잠금이 순서를 정해 주고, 뒤에 온
     * 쪽은 0 행을 받는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update InstructorNoteJpaEntity note
               set note.status = 'FINALIZED', note.finalizedAt = :finalizedAt
             where note.sessionId = :sessionId and note.status = 'DRAFT'
            """)
    int finalizeIfDraft(@Param("sessionId") Long sessionId, @Param("finalizedAt") Instant finalizedAt);
}
