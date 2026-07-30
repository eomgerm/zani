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
        int studentsCounted,
        double significantRatio,
        double confusedRatio,
        double missedRatio,
        double nonResponseRatio,
        double unmeasurableRatio,
        CoachingTipType selectedTipType,
        CoachingTranscript transcript,
        CoachingTip tip,
        CoachingTipUnavailableReason unavailableReason) {

    public CoachingHistory {
        if (sessionId <= 0 || triggerId == null || triggerId.isBlank() || triggeredAt == null) {
            throw new IllegalArgumentException("session, trigger id, and trigger time are required");
        }
        if (studentsCounted <= 0) {
            throw new IllegalArgumentException("the anonymous denominator must be positive");
        }
        requireRatio(significantRatio);
        requireRatio(confusedRatio);
        requireRatio(missedRatio);
        requireRatio(nonResponseRatio);
        requireRatio(unmeasurableRatio);
        if (transcript == null) {
            throw new IllegalArgumentException("transcript status is required");
        }
        if ((tip == null) == (unavailableReason == null)) {
            throw new IllegalArgumentException("exactly one of tip and unavailable reason is required");
        }
        if (tip != null && selectedTipType != tip.tipType()) {
            throw new IllegalArgumentException("selected and completed tip types must match");
        }
    }

    private static void requireRatio(double ratio) {
        if (!Double.isFinite(ratio) || ratio < 0 || ratio > 1) {
            throw new IllegalArgumentException("coaching ratios must be between 0 and 1");
        }
    }
}
