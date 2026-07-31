package com.a105.zani.coach.infrastructure.persistence;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.attention.application.port.CoachingTip;
import com.a105.zani.coach.application.port.StoreCoachingHistoryPort;
import com.a105.zani.coach.application.storehistory.CoachingHistory;
import com.a105.zani.coach.application.storehistory.CoachingResponseCounts;
import com.a105.zani.common.persistence.TsidGenerator;

/** Stores one session-owned coaching result and its exact anonymous response counts atomically. */
@Component
@RequiredArgsConstructor
public class CoachingHistoryPersistenceAdapter implements StoreCoachingHistoryPort {

    private static final String INSERT_HISTORY = """
            INSERT INTO coaching_histories
                (id, session_id, trigger_id, triggered_at, completed_at, denominator_count,
                 selected_tip_type, outcome_status, transcript_status, transcript_started_at,
                 transcript_ended_at, topic, tip_type, tip_title, tip_message, unavailable_reason,
                 created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE id = id
            """;

    private static final String INSERT_RESPONSE_COUNT = """
            INSERT INTO coaching_history_response_counts
                (id, coaching_history_id, response_type, response_count, created_at)
            VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6))
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean saveIfNew(CoachingHistory history) {
        long historyId = TsidGenerator.generate();
        CoachingTip tip = history.tip();

        int inserted = jdbcTemplate.update(
                INSERT_HISTORY,
                historyId,
                history.sessionId(),
                history.triggerId(),
                history.triggeredAt(),
                history.completedAt(),
                history.responseCounts().denominator(),
                history.selectedTipType() == null
                        ? null
                        : history.selectedTipType().name(),
                tip == null ? "TIP_UNAVAILABLE" : "TIP_DELIVERED",
                history.transcript().status().name(),
                history.transcript().startedAt(),
                history.transcript().endedAt(),
                history.topic(),
                tip == null ? null : tip.tipType().name(),
                tip == null ? null : tip.title(),
                tip == null ? null : tip.message(),
                history.unavailableReason() == null
                        ? null
                        : history.unavailableReason().name());

        if (inserted != 1) {
            return false;
        }

        jdbcTemplate.batchUpdate(
                INSERT_RESPONSE_COUNT,
                responseCounts(history.responseCounts()).stream()
                        .map(response ->
                                new Object[] {TsidGenerator.generate(), historyId, response.type(), response.count()})
                        .toList());
        return true;
    }

    private List<ResponseCount> responseCounts(CoachingResponseCounts counts) {
        return List.of(
                new ResponseCount("SIGNIFICANT", counts.significant()),
                new ResponseCount("CONFUSED", counts.confused()),
                new ResponseCount("MISSED", counts.missed()),
                new ResponseCount("NON_RESPONSE", counts.nonResponse()),
                new ResponseCount("UNMEASURABLE", counts.unmeasurable()));
    }

    private record ResponseCount(String type, int count) {}
}
