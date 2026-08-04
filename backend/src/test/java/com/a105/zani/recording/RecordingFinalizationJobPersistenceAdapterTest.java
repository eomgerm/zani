package com.a105.zani.recording;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.recording.application.finalizejob.FinalizationJobLease;
import com.a105.zani.recording.application.finalizejob.RecordingFinalizationJobPort;
import com.a105.zani.recording.domain.model.RecordingFinalizationStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class RecordingFinalizationJobPersistenceAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-04T09:00:00Z");

    @Autowired
    private RecordingFinalizationJobPort port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> sessionIds = new ArrayList<>();
    private final List<Long> memberIds = new ArrayList<>();

    @AfterEach
    void clean() {
        for (Long sessionId : sessionIds) {
            jdbcTemplate.update("DELETE FROM recording_finalization_jobs WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM recordings WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
        }
        for (Long memberId : memberIds) {
            jdbcTemplate.update("DELETE FROM members WHERE id = ?", memberId);
        }
    }

    @Test
    void 종료되고_녹화가_있는_세션만_멱등으로_대기열에_넣는다() {
        long ended = createSession("ENDED");
        insertRecording(ended);
        long live = createSession("LIVE");
        insertRecording(live);
        createSession("ENDED");

        port.enqueueEndedSessions(100, NOW);
        port.enqueueEndedSessions(100, NOW.plusSeconds(1));

        assertEquals(1, jobCount(ended));
        assertEquals(0, jobCount(live));
        assertTrue(port.findDueSessionIds(NOW.plusSeconds(1), 100).contains(ended));
    }

    @Test
    void 실행권_선점은_한_번만_성공하고_실제_worker_직전까지_attempt를_올리지_않는다() {
        long sessionId = queuedSession();

        FinalizationJobLease lease =
                port.tryClaim(sessionId, NOW.plusSeconds(300), NOW).orElseThrow();

        assertEquals(0, lease.attemptCount());
        assertTrue(port.tryClaim(sessionId, NOW.plusSeconds(300), NOW).isEmpty());
        FinalizationJobLease attempted =
                port.beginAttempt(lease, NOW.plusSeconds(1)).orElseThrow();
        assertEquals(1, attempted.attemptCount());
    }

    @Test
    void Egress_정리_대기는_attempt를_소모하지_않고_기한_뒤_다시_발견된다() {
        long sessionId = queuedSession();
        FinalizationJobLease lease =
                port.tryClaim(sessionId, NOW.plusSeconds(300), NOW).orElseThrow();

        assertTrue(port.markWaiting(lease, NOW.plusSeconds(30), NOW.plusSeconds(1)));

        assertFalse(port.findDueSessionIds(NOW.plusSeconds(29), 100).contains(sessionId));
        assertTrue(port.findDueSessionIds(NOW.plusSeconds(30), 100).contains(sessionId));
        assertEquals(0, integer(sessionId, "attempt_count"));
    }

    @Test
    void lease가_만료되어_회수되면_이전_실행의_늦은_결과를_fencing으로_버린다() {
        long sessionId = queuedSession();
        FinalizationJobLease first =
                port.tryClaim(sessionId, NOW.plusSeconds(10), NOW).orElseThrow();
        FinalizationJobLease second = port.tryClaim(sessionId, NOW.plusSeconds(30), NOW.plusSeconds(10))
                .orElseThrow();

        assertEquals(first.leaseToken() + 1, second.leaseToken());
        assertFalse(port.markFailed(first, "late failure", NOW.plusSeconds(11)));
        assertTrue(port.markFailed(second, "current failure", NOW.plusSeconds(11)));
        assertEquals(RecordingFinalizationStatus.FAILED.name(), text(sessionId, "status"));
    }

    @Test
    void 성공하면_manifest와_MP4_메타데이터를_함께_확정한다() {
        long sessionId = queuedSession();
        FinalizationJobLease lease =
                port.tryClaim(sessionId, NOW.plusSeconds(300), NOW).orElseThrow();
        FinalizationJobLease attempted =
                port.beginAttempt(lease, NOW.plusSeconds(1)).orElseThrow();
        String sha256 = "a".repeat(64);

        assertTrue(port.markCompleted(
                attempted,
                "/finalized/" + sessionId + "/manifest/tracks.json",
                "/finalized/" + sessionId + "/final/lecture.mp4",
                1234L,
                sha256,
                NOW.plusSeconds(10)));

        assertEquals(RecordingFinalizationStatus.COMPLETED.name(), text(sessionId, "status"));
        assertEquals(1234, integer(sessionId, "output_size_bytes"));
        assertEquals(sha256, text(sessionId, "output_sha256"));
    }

    @Test
    void worker_잠금_경합은_attempt에서_제외하고_다시_대기한다() {
        long sessionId = queuedSession();
        FinalizationJobLease lease =
                port.tryClaim(sessionId, NOW.plusSeconds(300), NOW).orElseThrow();
        FinalizationJobLease attempted =
                port.beginAttempt(lease, NOW.plusSeconds(1)).orElseThrow();

        assertTrue(port.markContended(attempted, NOW.plusSeconds(30), NOW.plusSeconds(2)));

        assertEquals(0, integer(sessionId, "attempt_count"));
        assertFalse(port.findDueSessionIds(NOW.plusSeconds(29), 100).contains(sessionId));
        assertTrue(port.findDueSessionIds(NOW.plusSeconds(30), 100).contains(sessionId));
    }

    private long queuedSession() {
        long sessionId = createSession("ENDED");
        insertRecording(sessionId);
        port.enqueueEndedSessions(100, NOW);
        assertEquals(1, jobCount(sessionId));
        return sessionId;
    }

    private long createSession(String status) {
        long memberId = TsidGenerator.generate();
        long sessionId = TsidGenerator.generate();
        memberIds.add(memberId);
        sessionIds.add(sessionId);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                memberId,
                "finalization-job-" + suffix,
                "finalization-job-" + suffix + "@example.invalid",
                "finalization job",
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status, started_at,"
                        + " ended_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'NOT_STARTED', ?, ?, ?, ?)",
                sessionId,
                memberId,
                "finalization job",
                suffix,
                status,
                java.sql.Timestamp.from(NOW.minusSeconds(3_600)),
                "ENDED".equals(status) ? java.sql.Timestamp.from(NOW) : null,
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        return sessionId;
    }

    private void insertRecording(long sessionId) {
        jdbcTemplate.update(
                "INSERT INTO recordings (id, session_id, livekit_egress_id, recording_type, attempt_number, status,"
                        + " created_at, updated_at) VALUES (?, ?, ?, 'TRACK', 1, 'COMPLETE', ?, ?)",
                TsidGenerator.generate(),
                sessionId,
                "EG_" + UUID.randomUUID(),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
    }

    private int integer(long sessionId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM recording_finalization_jobs WHERE session_id = ?",
                Integer.class,
                sessionId);
    }

    private int jobCount(long sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recording_finalization_jobs WHERE session_id = ?", Integer.class, sessionId);
    }

    private String text(long sessionId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM recording_finalization_jobs WHERE session_id = ?", String.class, sessionId);
    }
}
