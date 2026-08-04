package com.a105.zani.postclass.infrastructure.persistence.entity;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.a105.zani.common.infrastructure.persistence.BaseJpaEntity;

/**
 * 전사 청크 체크포인트 행(V13, S15P11A105-247).
 *
 * <p>{@code sessions}·{@code recording_files} 로 FK 를 두지 않는다. {@code pipeline_jobs}·{@code recording_outbox} 와 같은 이유다 —
 * 소비·정리가 독립적인 작업 테이블이라 다른 애그리게이트와 잠금으로 엮이지 않아야 한다. 그래서 연관 매핑도 두지 않고 식별자만 들고 있다.
 *
 * <p>{@code resultDocument} 는 JSON 컬럼이지만 필드는 {@code String} 이다. 직렬화를 어댑터가 명시적으로 하기 때문이다 — 세그먼트 형태가 이 작업의 계약이라 Hibernate
 * 의 매핑 규칙에 맡기지 않고 눈에 보이는 코드로 둔다.
 */
@Entity
@Table(name = "postclass_transcription_chunks")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PostClassTranscriptionChunkJpaEntity extends BaseJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "recording_file_id", nullable = false)
    private Long recordingFileId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "start_offset_ms", nullable = false)
    private Long startOffsetMs;

    @Column(name = "end_offset_ms", nullable = false)
    private Long endOffsetMs;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "lease_until", columnDefinition = "DATETIME(6)")
    private Instant leaseUntil;

    @Column(name = "next_attempt_at", columnDefinition = "DATETIME(6)")
    private Instant nextAttemptAt;

    @Column(name = "result_document", columnDefinition = "JSON")
    private String resultDocument;

    @Column(name = "last_error", length = 500)
    private String lastError;
}
