package com.a105.zani.attention.infrastructure.persistence.entity;

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

import com.a105.zani.common.infrastructure.persistence.BaseCreatedJpaEntity;
import com.a105.zani.session.infrastructure.persistence.entity.InteractionEventJpaEntity;

@Entity
@Table(name = "check_prompt_evidences")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CheckPromptEvidenceJpaEntity extends BaseCreatedJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "check_prompt_id", nullable = false)
    private Long checkPromptId;

    @Column(name = "attention_event_id")
    private Long attentionEventId;

    @Column(name = "interaction_event_id")
    private Long interactionEventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "check_prompt_id", referencedColumnName = "id", insertable = false, updatable = false)
    private CheckPromptJpaEntity checkPrompt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attention_event_id", referencedColumnName = "id", insertable = false, updatable = false)
    private AttentionEventJpaEntity attentionEvent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interaction_event_id", referencedColumnName = "id", insertable = false, updatable = false)
    private InteractionEventJpaEntity interactionEvent;

    @Column(name = "evidence_type", nullable = false, length = 50)
    private String evidenceType;
}
