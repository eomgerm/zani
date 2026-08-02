package com.a105.zani.coach.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    private static final String SELECT_HISTORY_ID = """
            SELECT id
              FROM coaching_histories
             WHERE session_id = ?
               AND trigger_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean saveIfNew(CoachingHistory history) {
        long historyId = TsidGenerator.generate();
        CoachingTip tip = history.tip();

        jdbcTemplate.update(
                INSERT_HISTORY,
                historyId,
                history.sessionId(),
                history.triggerId(),
                utc(history.triggeredAt()),
                utc(history.completedAt()),
                history.responseCounts().denominator(),
                history.selectedTipType() == null
                        ? null
                        : history.selectedTipType().name(),
                tip == null ? "TIP_UNAVAILABLE" : "TIP_DELIVERED",
                history.transcript().status().name(),
                utc(history.transcript().startedAt()),
                utc(history.transcript().endedAt()),
                history.topic(),
                tip == null ? null : tip.tipType().name(),
                tip == null ? null : tip.title(),
                tip == null ? null : tip.message(),
                history.unavailableReason() == null
                        ? null
                        : history.unavailableReason().name());

        Long storedHistoryId =
                jdbcTemplate.queryForObject(SELECT_HISTORY_ID, Long.class, history.sessionId(), history.triggerId());
        if (storedHistoryId == null || storedHistoryId.longValue() != historyId) {
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

    /**
     * {@code Instant} 를 UTC 벽시계로 바꿔 바인딩한다.
     *
     * <p>대상 컬럼은 모두 {@code DATETIME(6)} 이라 시간대를 담지 않고, 스키마 주석이 UTC 로 못박고 있다. 그런데 Connector/J 는 {@code Instant} 를 받으면 세션
     * 시간대로 옮겨 넣는다. JVM 이 Asia/Seoul 이면 9시간 밀린 값이 저장되고, 배포 장비 시간대에 따라 값이 달라진다.
     *
     * <p>{@code LocalDateTime} 으로 넘기면 드라이버가 변환하지 않고 그대로 넣는다. {@code created_at} 이 {@code UTC_TIMESTAMP(6)} 인 것과 같은 기준을
     * 맞춘다.
     */
    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
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
