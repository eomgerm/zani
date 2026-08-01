package com.a105.zani.coach.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.attention.application.port.CoachingTip;
import com.a105.zani.attention.application.port.CoachingTipType;
import com.a105.zani.attention.application.port.CoachingTipUnavailableReason;
import com.a105.zani.coach.application.storehistory.CoachingHistory;
import com.a105.zani.coach.application.storehistory.CoachingResponseCounts;
import com.a105.zani.coach.application.storehistory.CoachingTranscript;
import com.a105.zani.coach.application.storehistory.PersistCoachingHistoryUseCase;
import com.a105.zani.common.persistence.TsidGenerator;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class CoachingHistoryPersistenceIntegrationTest {

    private static final Instant SESSION_STARTED_AT = Instant.parse("2026-07-30T01:00:00Z");

    @Autowired
    private PersistCoachingHistoryUseCase persistCoachingHistoryUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void storesThreeAnonymousTimelineRowsAndIgnoresADuplicateTrigger() {
        long sessionId = createSession();
        CoachingHistory first = completed(sessionId, "trigger-1", 1);
        CoachingHistory second = unavailable(sessionId, "trigger-2", 2);
        CoachingHistory third = completed(sessionId, "trigger-3", 3);

        persistCoachingHistoryUseCase.persist(first);
        persistCoachingHistoryUseCase.persist(second);
        persistCoachingHistoryUseCase.persist(third);
        persistCoachingHistoryUseCase.persist(second);

        List<String> triggerIds = jdbcTemplate.queryForList(
                "SELECT trigger_id FROM coaching_histories WHERE session_id = ? ORDER BY triggered_at",
                String.class,
                sessionId);
        assertThat(triggerIds).containsExactly("trigger-1", "trigger-2", "trigger-3");

        Integer responseRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coaching_history_response_counts c"
                        + " JOIN coaching_histories h ON h.id = c.coaching_history_id WHERE h.session_id = ?",
                Integer.class,
                sessionId);
        assertThat(responseRows).isEqualTo(15);

        // 컬럼은 DATETIME 이라 시간대를 담지 않는다. 문자열로 읽어 UTC 그대로 들어갔는지 본다.
        // ORDER BY 만 보면 일괄 시프트를 잡지 못한다 — 모두 같은 만큼 밀려도 순서는 그대로다.
        Map<String, Object> storedTimes = jdbcTemplate.queryForMap("""
                SELECT DATE_FORMAT(triggered_at, '%Y-%m-%dT%H:%i:%s') AS triggered,
                       DATE_FORMAT(completed_at, '%Y-%m-%dT%H:%i:%s') AS completed,
                       DATE_FORMAT(transcript_started_at, '%Y-%m-%dT%H:%i:%s') AS transcriptStarted,
                       DATE_FORMAT(transcript_ended_at, '%Y-%m-%dT%H:%i:%s') AS transcriptEnded
                  FROM coaching_histories WHERE trigger_id = ?
                """, "trigger-1");
        assertThat(storedTimes)
                .containsEntry("triggered", "2026-07-30T01:01:00")
                .containsEntry("completed", "2026-07-30T01:01:01")
                .containsEntry("transcriptStarted", "2026-07-30T01:00:30")
                .containsEntry("transcriptEnded", "2026-07-30T01:01:00");

        String unavailableReason = jdbcTemplate.queryForObject(
                "SELECT unavailable_reason FROM coaching_histories WHERE session_id = ? AND trigger_id = ?",
                String.class,
                sessionId,
                "trigger-2");
        assertThat(unavailableReason).isEqualTo(CoachingTipUnavailableReason.LOW_CONFIDENCE.name());

        Integer confusedCount = jdbcTemplate.queryForObject(
                "SELECT c.response_count FROM coaching_history_response_counts c"
                        + " JOIN coaching_histories h ON h.id = c.coaching_history_id"
                        + " WHERE h.session_id = ? AND h.trigger_id = ? AND c.response_type = 'CONFUSED'",
                Integer.class,
                sessionId,
                "trigger-1");
        assertThat(confusedCount).isEqualTo(3);

        List<String> privateColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = DATABASE()"
                        + " AND table_name IN ('coaching_histories', 'coaching_history_response_counts')"
                        + " AND (column_name LIKE '%student%' OR column_name LIKE '%participant%')",
                String.class);
        assertThat(privateColumns).isEmpty();

        List<String> transcriptTextColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = DATABASE() AND table_name = 'coaching_histories'"
                        + " AND column_name = 'transcript_text'",
                String.class);
        assertThat(transcriptTextColumns).isEmpty();
    }

    @Test
    void permitsTheSameTriggerIdInDifferentSessions() {
        long firstSessionId = createSession();
        long secondSessionId = createSession();

        persistCoachingHistoryUseCase.persist(completed(firstSessionId, "same-trigger", 1));
        persistCoachingHistoryUseCase.persist(completed(secondSessionId, "same-trigger", 1));

        Integer stored = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coaching_histories WHERE trigger_id = 'same-trigger'", Integer.class);
        assertThat(stored).isEqualTo(2);
    }

    private long createSession() {
        long memberId = TsidGenerator.generate();
        long sessionId = TsidGenerator.generate();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                memberId,
                "coach-history-" + suffix,
                "coach-history-" + suffix + "@example.com",
                "coach history test",
                java.sql.Timestamp.from(SESSION_STARTED_AT),
                java.sql.Timestamp.from(SESSION_STARTED_AT));
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status, started_at,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, 'LIVE', 'NOT_STARTED', ?, ?, ?)",
                sessionId,
                memberId,
                "coaching history",
                suffix,
                java.sql.Timestamp.from(SESSION_STARTED_AT),
                java.sql.Timestamp.from(SESSION_STARTED_AT),
                java.sql.Timestamp.from(SESSION_STARTED_AT));
        return sessionId;
    }

    private CoachingHistory completed(long sessionId, String triggerId, long minute) {
        Instant triggeredAt = SESSION_STARTED_AT.plusSeconds(minute * 60);
        return history(
                sessionId,
                triggerId,
                triggeredAt,
                CoachingTranscript.transcribed(triggeredAt.minusSeconds(30).toEpochMilli(), triggeredAt.toEpochMilli()),
                new CoachingTip(CoachingTipType.CONFUSED, "title", "message", "concept"),
                null);
    }

    private CoachingHistory unavailable(long sessionId, String triggerId, long minute) {
        return history(
                sessionId,
                triggerId,
                SESSION_STARTED_AT.plusSeconds(minute * 60),
                CoachingTranscript.transcribed(
                        SESSION_STARTED_AT.plusSeconds(minute * 60 - 30).toEpochMilli(),
                        SESSION_STARTED_AT.plusSeconds(minute * 60).toEpochMilli()),
                null,
                CoachingTipUnavailableReason.LOW_CONFIDENCE);
    }

    private CoachingHistory history(
            long sessionId,
            String triggerId,
            Instant triggeredAt,
            CoachingTranscript transcript,
            CoachingTip tip,
            CoachingTipUnavailableReason unavailableReason) {
        return new CoachingHistory(
                sessionId,
                triggerId,
                triggeredAt,
                triggeredAt.plusSeconds(1),
                new CoachingResponseCounts(10, 4, 3, 1, 0, 0),
                CoachingTipType.CONFUSED,
                transcript,
                "concept",
                tip,
                unavailableReason);
    }
}
