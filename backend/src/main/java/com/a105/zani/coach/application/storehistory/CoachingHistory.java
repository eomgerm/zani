package com.a105.zani.coach.application.storehistory;

import java.time.Instant;

import com.a105.zani.attention.application.port.CoachingTip;
import com.a105.zani.attention.application.port.CoachingTipType;
import com.a105.zani.attention.application.port.CoachingTipUnavailableReason;

/** Anonymous, report-ready snapshot produced once for each completed coaching trigger. */
public record CoachingHistory(
        long sessionId,
        String triggerId,
        Instant triggeredAt,
        Instant completedAt,
        CoachingResponseCounts responseCounts,
        CoachingTipType selectedTipType,
        CoachingTranscript transcript,
        String topic,
        CoachingTip tip,
        CoachingTipUnavailableReason unavailableReason) {

    public CoachingHistory {
        if (sessionId <= 0 || triggerId == null || triggerId.isBlank() || triggeredAt == null || completedAt == null) {
            throw new IllegalArgumentException("session, trigger id, trigger time, and completion time are required");
        }
        if (completedAt.isBefore(triggeredAt)) {
            throw new IllegalArgumentException("completion time must not precede trigger time");
        }
        if (responseCounts == null || transcript == null) {
            throw new IllegalArgumentException("response counts and transcript status are required");
        }
        if ((tip == null) == (unavailableReason == null)) {
            throw new IllegalArgumentException("exactly one of tip and unavailable reason is required");
        }
        if (tip != null && selectedTipType != tip.tipType()) {
            throw new IllegalArgumentException("selected and completed tip types must match");
        }
        if (topic != null && topic.isBlank()) {
            topic = null;
        }
    }
}
