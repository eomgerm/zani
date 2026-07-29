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

import com.a105.zani.common.infrastructure.persistence.BaseJpaEntity;
import com.a105.zani.member.infrastructure.persistence.entity.MemberJpaEntity;
import com.a105.zani.session.domain.model.SessionParticipantRole;

@Entity
@Table(name = "session_participants")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SessionParticipantJpaEntity extends BaseJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", referencedColumnName = "id", insertable = false, updatable = false)
    private SessionJpaEntity session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", referencedColumnName = "id", insertable = false, updatable = false)
    private MemberJpaEntity member;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private SessionParticipantRole role;

    /** LiveKit 첫 연결 성공 시각. API 입장만 한 상태에서는 비어 있다. */
    @Column(name = "first_joined_at", columnDefinition = "DATETIME(6)")
    private Instant firstJoinedAt;

    @Column(name = "last_joined_at", columnDefinition = "DATETIME(6)")
    private Instant lastJoinedAt;

    @Column(name = "last_left_at", columnDefinition = "DATETIME(6)")
    private Instant lastLeftAt;

    @Column(name = "last_accessed_at", columnDefinition = "DATETIME(6)")
    private Instant lastAccessedAt;
}
