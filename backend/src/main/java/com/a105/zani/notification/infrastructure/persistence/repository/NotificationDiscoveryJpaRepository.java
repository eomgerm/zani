package com.a105.zani.notification.infrastructure.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.notification.infrastructure.persistence.entity.NotificationOutboxJpaEntity;

/**
 * producer 의 교차 모듈 읽기. session_reports·sessions·session_participants·members 는 다른 모듈의 테이블이라 네이티브 SQL 로만 읽고 쓰지 않는다. 소프트
 * 삭제된 행은 네이티브 쿼리가 @SQLRestriction 을 우회하므로 deleted_at IS NULL 을 명시한다.
 */
public interface NotificationDiscoveryJpaRepository extends JpaRepository<NotificationOutboxJpaEntity, Long> {

    /** 리포트가 공개 완료됐지만 아직 REPORT_READY 알림이 등록되지 않은 세션. */
    @Query(
            value = "SELECT sr.session_id FROM session_reports sr"
                    + " JOIN sessions s ON s.id = sr.session_id AND s.deleted_at IS NULL"
                    + " WHERE sr.published_at IS NOT NULL"
                    + " AND NOT EXISTS ("
                    + "   SELECT 1 FROM notification_outbox n"
                    + "   WHERE n.session_id = sr.session_id AND n.type = :type)"
                    + " ORDER BY sr.session_id ASC LIMIT :limit",
            nativeQuery = true)
    List<Long> findReadyReportSessionsWithoutNotification(@Param("type") String type, @Param("limit") int limit);

    /** 세션의 학생 수신자(역할 STUDENT). 리포트 알림 수신을 끈(report_email_enabled = FALSE) 회원은 발송 대상에서 제외한다. */
    @Query(
            value = "SELECT p.member_id AS memberId, m.email AS email, m.display_name AS displayName"
                    + " FROM session_participants p"
                    + " JOIN members m ON m.id = p.member_id"
                    + " WHERE p.session_id = :sessionId AND p.role = 'STUDENT' AND m.deleted_at IS NULL"
                    + " AND m.report_email_enabled = TRUE"
                    + " ORDER BY p.id ASC",
            nativeQuery = true)
    List<ReportRecipientRow> findRecipients(@Param("sessionId") Long sessionId);
}
