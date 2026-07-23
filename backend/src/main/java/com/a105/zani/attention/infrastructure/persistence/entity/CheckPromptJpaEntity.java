package com.a105.zani.attention.infrastructure.persistence.entity;

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
import com.a105.zani.session.infrastructure.persistence.entity.SessionParticipantJpaEntity;

@Entity
@Table(name = "check_prompts")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CheckPromptJpaEntity extends BaseJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "session_participant_id", nullable = false)
    private Long sessionParticipantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
        @JoinColumn(name = "session_id", referencedColumnName = "session_id", insertable = false, updatable = false),
        @JoinColumn(name = "session_participant_id", referencedColumnName = "id", insertable = false, updatable = false)
    })
    private SessionParticipantJpaEntity sessionParticipant;

    @Column(name = "trigger_type", nullable = false, length = 50)
    private String triggerType;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "response", length = 30)
    private String response;

    @Column(name = "shown_offset_ms", nullable = false)
    private Long shownOffsetMs;

    @Column(name = "responded_offset_ms")
    private Long respondedOffsetMs;
}
