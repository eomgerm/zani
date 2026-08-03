package com.a105.zani.notification.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.notification.application.port.ReportRecipient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * discovery 조인 쿼리의 DB 수준 계약 검증. session_reports·sessions·session_participants·members 를 가로지르는 네이티브 조인이라 조인·컬럼명·enum
 * 리터럴('STUDENT')·soft-delete 필터가 실제로 맞는지는 단위 테스트(stub)로는 잡히지 않는다. 로컬 MySQL이 떠 있어야 통과하며, 테스트 트랜잭션은 종료 시 롤백된다.
 */
@SpringBootTest
class NotificationDiscoveryQueryAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-02T09:00:00Z");
    private static final int LARGE_LIMIT = 100_000;

    @Autowired
    private NotificationDiscoveryQueryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Transactional
    void findsSessionsWithAPublishedReportAndNoNotificationYet() {
        long ready = sessionWithReport(true);
        long notPublished = sessionWithReport(false);
        long alreadyNotified = sessionWithReport(true);
        insertOutboxRow(alreadyNotified, TsidGenerator.generate());
        long softDeleted = sessionWithReport(true);
        softDeleteSession(softDeleted);

        List<Long> found = adapter.findReadyReportSessionsWithoutNotification(LARGE_LIMIT);

        assertTrue(found.contains(ready), "공개된 리포트 + 알림 미등록 세션은 포함돼야 한다");
        assertFalse(found.contains(notPublished), "published_at 이 NULL 이면 제외");
        assertFalse(found.contains(alreadyNotified), "이미 알림이 등록된 세션은 제외");
        assertFalse(found.contains(softDeleted), "소프트 삭제된 세션은 제외");
    }

    @Test
    @Transactional
    void findsOnlyLiveStudentsAsRecipients() {
        long hostMember = insertMember("host@zani.app", "강사", false);
        long sessionId = insertSession(hostMember, false);
        long student = insertMember("student@zani.app", "학생", false);
        long deletedStudent = insertMember("gone@zani.app", "탈퇴생", true);
        insertParticipant(sessionId, student, "STUDENT");
        insertParticipant(sessionId, deletedStudent, "STUDENT");
        insertParticipant(sessionId, hostMember, "INSTRUCTOR");

        List<ReportRecipient> recipients = adapter.findRecipients(sessionId);

        assertEquals(1, recipients.size(), "역할 STUDENT + 미삭제 회원만");
        ReportRecipient only = recipients.get(0);
        assertEquals(student, only.memberId());
        assertEquals("student@zani.app", only.email());
        assertEquals("학생", only.displayName());
    }

    @Test
    @Transactional
    void excludesStudentsWhoTurnedOffReportEmail() {
        long hostMember = insertMember("host2@zani.app", "강사", false);
        long sessionId = insertSession(hostMember, false);
        long optedIn = insertMember("in@zani.app", "수신", false);
        long optedOut = insertMemberWithReportEmail("out@zani.app", "미수신", false);
        insertParticipant(sessionId, optedIn, "STUDENT");
        insertParticipant(sessionId, optedOut, "STUDENT");

        List<ReportRecipient> recipients = adapter.findRecipients(sessionId);

        assertEquals(1, recipients.size(), "리포트 알림을 끈 학생은 발송 대상에서 제외된다");
        assertEquals(optedIn, recipients.get(0).memberId());
    }

    /** 강사(host) 회원과 세션을 만들고, 리포트를 공개/미공개로 붙인다. */
    private long sessionWithReport(boolean published) {
        long host = insertMember("host-" + suffix() + "@zani.app", "강사", false);
        long sessionId = insertSession(host, false);
        insertSessionReport(sessionId, published);
        return sessionId;
    }

    private long insertMember(String email, String displayName, boolean deleted) {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at, deleted_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                id,
                "sub-" + suffix(),
                email,
                displayName,
                ts(NOW),
                ts(NOW),
                deleted ? ts(NOW) : null);
        return id;
    }

    /** report_email_enabled 를 명시해 넣는다(수신 OFF 게이트 검증용). */
    private long insertMemberWithReportEmail(String email, String displayName, boolean deleted) {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, report_email_enabled, created_at,"
                        + " updated_at, deleted_at) VALUES (?, ?, ?, ?, FALSE, ?, ?, ?)",
                id,
                "sub-" + suffix(),
                email,
                displayName,
                ts(NOW),
                ts(NOW),
                deleted ? ts(NOW) : null);
        return id;
    }

    private long insertSession(long hostMemberId, boolean deleted) {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status, started_at,"
                        + " created_at, updated_at, deleted_at) VALUES (?, ?, ?, ?, 'ENDED', 'COMPLETED', ?, ?, ?, ?)",
                id,
                hostMemberId,
                "리포트 알림 테스트",
                suffix(),
                ts(NOW),
                ts(NOW),
                ts(NOW),
                deleted ? ts(NOW) : null);
        return id;
    }

    private void softDeleteSession(long sessionId) {
        jdbcTemplate.update("UPDATE sessions SET deleted_at = ? WHERE id = ?", ts(NOW), sessionId);
    }

    private void insertSessionReport(long sessionId, boolean published) {
        jdbcTemplate.update(
                "INSERT INTO session_reports (id, session_id, summary, published_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                TsidGenerator.generate(),
                sessionId,
                "요약",
                published ? ts(NOW) : null,
                ts(NOW),
                ts(NOW));
    }

    private void insertParticipant(long sessionId, long memberId, String role) {
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at, created_at,"
                        + " updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                TsidGenerator.generate(),
                sessionId,
                memberId,
                role,
                ts(NOW),
                ts(NOW),
                ts(NOW));
    }

    private void insertOutboxRow(long sessionId, long memberId) {
        jdbcTemplate.update(
                "INSERT INTO notification_outbox (id, session_id, member_id, email, display_name, type, dedup_key,"
                        + " status, attempt_count, next_attempt_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'REPORT_READY', ?, 'PENDING', 0, ?, ?, ?)",
                TsidGenerator.generate(),
                sessionId,
                memberId,
                "x@zani.app",
                "x",
                sessionId + ":" + memberId + ":REPORT_READY",
                ts(NOW),
                ts(NOW),
                ts(NOW));
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
