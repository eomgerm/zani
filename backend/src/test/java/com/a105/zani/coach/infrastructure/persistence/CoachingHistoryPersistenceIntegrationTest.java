package com.a105.zani.coach.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
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
import com.a105.zani.coach.application.storehistory.CoachingTranscript;
import com.a105.zani.coach.application.storehistory.StoreCoachingHistoryUseCase;
import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.member.infrastructure.persistence.entity.MemberJpaEntity;
import com.a105.zani.member.infrastructure.persistence.repository.MemberJpaRepository;
import com.a105.zani.session.domain.model.SessionAnalysisStatus;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;
import com.a105.zani.session.infrastructure.persistence.repository.SessionJpaRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class CoachingHistoryPersistenceIntegrationTest {

    private static final Instant SESSION_STARTED_AT = Instant.parse("2026-07-30T01:00:00Z");

    @Autowired
    private StoreCoachingHistoryUseCase storeCoachingHistoryUseCase;

    @Autowired
    private MemberJpaRepository memberJpaRepository;

    @Autowired
    private SessionJpaRepository sessionJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void storesThreeAnonymousTimelineRowsAndIgnoresADuplicateTrigger() {
        long sessionId = createSession();
        CoachingHistory first = completed(sessionId, "trigger-1", 1);
        CoachingHistory second = unavailable(sessionId, "trigger-2", 2);
        CoachingHistory third = completed(sessionId, "trigger-3", 3);

        storeCoachingHistoryUseCase.store(first);
        storeCoachingHistoryUseCase.store(second);
        storeCoachingHistoryUseCase.store(third);
        storeCoachingHistoryUseCase.store(second);

        List<String> triggerIds = jdbcTemplate.queryForList(
                "SELECT trigger_id FROM group_alerts WHERE session_id = ? ORDER BY triggered_at",
                String.class,
                sessionId);
        assertThat(triggerIds).containsExactly("trigger-1", "trigger-2", "trigger-3");

        Integer responseRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM group_alert_response_counts c"
                        + " JOIN group_alerts a ON a.id = c.group_alert_id WHERE a.session_id = ?",
                Integer.class,
                sessionId);
        assertThat(responseRows).isEqualTo(12);

        String unavailableReason = jdbcTemplate.queryForObject(
                "SELECT unavailable_reason FROM group_alerts WHERE trigger_id = ?", String.class, "trigger-2");
        assertThat(unavailableReason).isEqualTo(CoachingTipUnavailableReason.LOW_CONFIDENCE.name());

        List<String> privateColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = DATABASE() AND table_name IN ('group_alerts', 'group_alert_response_counts')"
                        + " AND (column_name LIKE '%student%' OR column_name LIKE '%participant%')",
                String.class);
        assertThat(privateColumns).isEmpty();
    }

    private long createSession() {
        long memberId = TsidGenerator.generate();
        long sessionId = TsidGenerator.generate();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        memberJpaRepository.saveAndFlush(MemberJpaEntity.builder()
                .id(memberId)
                .googleSubject("coach-history-" + suffix)
                .email("coach-history-" + suffix + "@example.com")
                .displayName("coach history test")
                .build());
        sessionJpaRepository.saveAndFlush(SessionJpaEntity.builder()
                .id(sessionId)
                .hostMemberId(memberId)
                .title("coaching history")
                .inviteCode(suffix)
                .status(SessionStatus.LIVE)
                .analysisStatus(SessionAnalysisStatus.NOT_STARTED)
                .startedAt(SESSION_STARTED_AT)
                .build());
        return sessionId;
    }

    private CoachingHistory completed(long sessionId, String triggerId, long minute) {
        Instant triggeredAt = SESSION_STARTED_AT.plusSeconds(minute * 60);
        return history(
                sessionId,
                triggerId,
                triggeredAt,
                CoachingTranscript.transcribed(
                        "anonymous instructor transcript " + minute,
                        triggeredAt.minusSeconds(30).toEpochMilli(),
                        triggeredAt.toEpochMilli()),
                new CoachingTip(CoachingTipType.CONFUSED, "title", "message", "concept"),
                null);
    }

    private CoachingHistory unavailable(long sessionId, String triggerId, long minute) {
        return history(
                sessionId,
                triggerId,
                SESSION_STARTED_AT.plusSeconds(minute * 60),
                CoachingTranscript.transcribed(
                        "anonymous instructor transcript " + minute,
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
                10,
                0.4,
                0.3,
                0.1,
                0.0,
                0.0,
                CoachingTipType.CONFUSED,
                transcript,
                tip,
                unavailableReason);
    }
}
