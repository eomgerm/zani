package com.a105.zani.postclass.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.postclass.infrastructure.persistence.entity.TranscriptJpaEntity;

/** 최종 전사 쿼리(S15P11A105-247). */
public interface TranscriptJpaRepository extends JpaRepository<TranscriptJpaEntity, Long> {

    /**
     * 세션당 한 행을 보장하며 저장한다. 이미 있으면 문서를 교체한다.
     *
     * <p>{@code UK_TRANSCRIPTS_SESSION} 이 있어 "있는지 보고 없으면 넣는다" 로는 부족하다. 조회와 삽입 사이에 다른 실행이 넣으면 flush 예외가 트랜잭션을
     * rollback-only 로 만들어 호출자의 앞선 작업까지 되돌아간다. 한 문장으로 하면 그 창이 없다.
     *
     * <p>{@code created_at} 은 갱신하지 않는다. 처음 전사가 만들어진 시각이고, 재조립이 그것을 덮으면 "이 세션은 언제부터 전사가 있었나" 를 잃는다.
     */
    @Modifying
    @Query(
            value = "INSERT INTO transcripts (id, session_id, transcript_document, created_at, updated_at)"
                    + " VALUES (:id, :sessionId, CAST(:document AS JSON), :now, :now)"
                    + " ON DUPLICATE KEY UPDATE transcript_document = VALUES(transcript_document),"
                    + "  updated_at = VALUES(updated_at)",
            nativeQuery = true)
    int upsert(
            @Param("id") Long id,
            @Param("sessionId") Long sessionId,
            @Param("document") String document,
            @Param("now") Instant now);

    Optional<TranscriptJpaEntity> findBySessionId(Long sessionId);
}
