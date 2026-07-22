package com.a105.zani.session.infrastructure.persistence.entity;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.a105.zani.common.infrastructure.persistence.BaseJpaEntity;

@Entity
@Table(name = "instructor_notes")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class InstructorNoteJpaEntity extends BaseJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "instructor_participant_id", nullable = false)
    private Long instructorParticipantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
        @JoinColumn(name = "session_id", referencedColumnName = "session_id", insertable = false, updatable = false),
        @JoinColumn(
                name = "instructor_participant_id",
                referencedColumnName = "id",
                insertable = false,
                updatable = false)
    })
    private SessionParticipantJpaEntity instructorParticipant;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "last_edited_at", columnDefinition = "DATETIME(6)")
    private Instant lastEditedAt;

    @Column(name = "finalized_at", columnDefinition = "DATETIME(6)")
    private Instant finalizedAt;
}
