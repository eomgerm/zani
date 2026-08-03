package com.a105.zani.coach.application.storehistory;

/** Exact anonymous counts captured at the moment a coaching trigger opens. */
public record CoachingResponseCounts(
        int denominator, int significant, int confused, int missed, int nonResponse, int unmeasurable) {

    public CoachingResponseCounts {
        if (denominator <= 0) {
            throw new IllegalArgumentException("the anonymous denominator must be positive");
        }
        requireCount(significant, denominator);
        requireCount(confused, denominator);
        requireCount(missed, denominator);
        requireCount(nonResponse, denominator);
        requireCount(unmeasurable, denominator);
    }

    private static void requireCount(int count, int denominator) {
        if (count < 0 || count > denominator) {
            throw new IllegalArgumentException("coaching counts must be between zero and the denominator");
        }
    }
}
