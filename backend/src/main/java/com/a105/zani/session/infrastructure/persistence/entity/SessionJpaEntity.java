package com.a105.zani.session.infrastructure.persistence.entity;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import com.a105.zani.common.infrastructure.persistence.BaseSoftDeletableJpaEntity;
import com.a105.zani.member.infrastructure.persistence.entity.MemberJpaEntity;
import com.a105.zani.session.domain.model.SessionAnalysisStatus;
import com.a105.zani.session.domain.model.SessionStatus;

@Entity
@Table(name = "sessions")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SessionJpaEntity extends BaseSoftDeletableJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "host_member_id", nullable = false)
    private Long hostMemberId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_member_id", referencedColumnName = "id", insertable = false, updatable = false)
    private MemberJpaEntity hostMember;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "invite_code", nullable = false, columnDefinition = "CHAR(8)")
    private String inviteCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private SessionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false, length = 30)
    private SessionAnalysisStatus analysisStatus;

    @Column(name = "started_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant startedAt;

    @Column(name = "ended_at", columnDefinition = "DATETIME(6)")
    private Instant endedAt;

    @Column(name = "note_due_at", columnDefinition = "DATETIME(6)")
    private Instant noteDueAt;

    @Column(name = "retention_expires_at", columnDefinition = "DATETIME(6)")
    private Instant retentionExpiresAt;
}
