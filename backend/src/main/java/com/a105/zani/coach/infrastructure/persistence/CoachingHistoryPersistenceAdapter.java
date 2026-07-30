package com.a105.zani.coach.infrastructure.persistence;

import java.math.BigDecimal;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.attention.application.port.CoachingTip;
import com.a105.zani.coach.application.port.StoreCoachingHistoryPort;
import com.a105.zani.coach.application.storehistory.CoachingHistory;
import com.a105.zani.common.persistence.TsidGenerator;

/** Stores one anonymous coaching snapshot and its four aggregate response counts atomically. */
@Component
@RequiredArgsConstructor
public class CoachingHistoryPersistenceAdapter implements StoreCoachingHistoryPort {

    private static final long SIGNAL_WINDOW_MS = 5 * 60 * 1000L;

    private static final String INSERT_HISTORY = """
            INSERT IGNORE INTO group_alerts
                (id, session_id, alert_type, window_started_offset_ms, window_ended_offset_ms,
                 numerator_count, denominator_count, occurred_offset_ms, created_at,
                 trigger_id, triggered_at, significant_ratio, confused_ratio, missed_ratio,
                 non_response_ratio, unmeasurable_ratio, transcript_status, transcript_text,
                 transcript_started_at, transcript_ended_at, tip_type, tip_title, tip_message,
                 target_concept, unavailable_reason)
            SELECT ?, s.id, ?,
                   GREATEST((TIMESTAMPDIFF(MICROSECOND, s.started_at, ?) DIV 1000) - ?, 0),
                   GREATEST(TIMESTAMPDIFF(MICROSECOND, s.started_at, ?) DIV 1000, 0),
                   ?, ?,
                   GREATEST(TIMESTAMPDIFF(MICROSECOND, s.started_at, ?) DIV 1000, 0),
                   UTC_TIMESTAMP(6),
                   ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
              FROM sessions s
             WHERE s.id = ?
            """;

    private static final String INSERT_RESPONSE_COUNT = """
            INSERT INTO group_alert_response_counts
                (id, group_alert_id, response_type, response_count, created_at)
            VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6))
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean saveIfNew(CoachingHistory history) {
        long groupAlertId = TsidGenerator.generate();
        CoachingTip tip = history.tip();

        int inserted = jdbcTemplate.update(
                INSERT_HISTORY,
                groupAlertId,
                history.selectedTipType() == null
                        ? "UNKNOWN"
                        : history.selectedTipType().name(),
                history.triggeredAt(),
                SIGNAL_WINDOW_MS,
                history.triggeredAt(),
                count(history.significantRatio(), history.studentsCounted()),
                history.studentsCounted(),
                history.triggeredAt(),
                history.triggerId(),
                history.triggeredAt(),
                decimal(history.significantRatio()),
                decimal(history.confusedRatio()),
                decimal(history.missedRatio()),
                decimal(history.nonResponseRatio()),
                decimal(history.unmeasurableRatio()),
                history.transcript().status().name(),
                history.transcript().text(),
                history.transcript().startedAt(),
                history.transcript().endedAt(),
                tip == null ? null : tip.tipType().name(),
                tip == null ? null : tip.title(),
                tip == null ? null : tip.message(),
                tip == null ? null : tip.targetConcept(),
                history.unavailableReason() == null
                        ? null
                        : history.unavailableReason().name(),
                history.sessionId());

        if (inserted == 0) {
            return false;
        }

        jdbcTemplate.batchUpdate(
                INSERT_RESPONSE_COUNT,
                responseCounts(history).stream()
                        .map(response ->
                                new Object[] {TsidGenerator.generate(), groupAlertId, response.type(), response.count()
                                })
                        .toList());
        return true;
    }

    private List<ResponseCount> responseCounts(CoachingHistory history) {
        int denominator = history.studentsCounted();
        return List.of(
                new ResponseCount("CONFUSED", count(history.confusedRatio(), denominator)),
                new ResponseCount("MISSED", count(history.missedRatio(), denominator)),
                new ResponseCount("NON_RESPONSE", count(history.nonResponseRatio(), denominator)),
                new ResponseCount("UNMEASURABLE", count(history.unmeasurableRatio(), denominator)));
    }

    private int count(double ratio, int denominator) {
        return (int) Math.round(ratio * denominator);
    }

    private BigDecimal decimal(double ratio) {
        return BigDecimal.valueOf(ratio);
    }

    private record ResponseCount(String type, int count) {}
}
