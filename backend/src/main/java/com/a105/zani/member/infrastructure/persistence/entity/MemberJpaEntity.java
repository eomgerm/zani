package com.a105.zani.member.infrastructure.persistence.entity;

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

import com.a105.zani.common.infrastructure.persistence.BaseSoftDeletableJpaEntity;

@Entity
@Table(name = "members")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberJpaEntity extends BaseSoftDeletableJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "google_subject", nullable = false, length = 255)
    private String googleSubject;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Column(name = "report_email_enabled", nullable = false)
    private boolean reportEmailEnabled;

    @Column(name = "retention_expires_at", columnDefinition = "DATETIME(6)")
    private Instant retentionExpiresAt;
}
