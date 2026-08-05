package com.a105.zani.postclass.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.a105.zani.common.infrastructure.persistence.BaseJpaEntity;
import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;

/**
 * 최종 전사 행({@code transcripts}).
 *
 * <p>{@code transcriptDocument} 는 JSON 컬럼이지만 필드는 {@code String} 이다. {@code Map<String, Object>} 로 두면 Hibernate 가 임의의
 * 구조를 그대로 받아 주는데, 문서 형태는 S15P11A105-247 의 계약이고 하류 8건이 그것을 읽는다. 직렬화를 어댑터가 {@code TranscriptDocument} 로 명시적으로 하면 형태를 벗어난
 * 쓰기가 컴파일 단계에서 막힌다. {@code PostClassTranscriptionChunkJpaEntity} 와 같은 판단이다.
 */
@Entity
@Table(name = "transcripts")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TranscriptJpaEntity extends BaseJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", referencedColumnName = "id", insertable = false, updatable = false)
    private SessionJpaEntity session;

    @Column(name = "transcript_document", nullable = false, columnDefinition = "JSON")
    private String transcriptDocument;
}
