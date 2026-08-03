package com.a105.zani.attention.domain.model.timeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 겹치지 않는 30초 구간마다 4단계 판정의 단계 평균을 낸다(설계 문서 §2.8·§2.9).
 *
 * <p>이동창이 아니다. 그리고 {@code GOOD} 비율도 아니다 — 값은 1.00~4.00 이며 퍼센트로 환산하지 않는다.
 *
 * <p>{@code UNMEASURABLE}·{@code CAMERA_OFF}·{@code DETECTOR_UNAVAILABLE} 은 단계가 없어 평균 대상이 아니다. 이들을 1단계로 바꾸지 않는다 — 낮은
 * 참여도와 측정 실패는 다른 사건이다.
 *
 * <p>프롬프트 응답도 단계가 없어 평균에 들어가지 않는다(§2.11). 학생이 "헷갈려요" 라고 답해도 이 값은 떨어지지 않으며, 그 신호는 확인 필요 비율이 따로 나른다.
 */
public final class FocusFlowCalculator {

    /** 판정 한 건이 덮는 시간. 게이트를 시간으로 재기 때문에 필요하다. */
    private static final long SLOT_MS = 10_000L;

    private FocusFlowCalculator() {}

    public static List<FocusBucket> calculate(ParticipantReplay replay, long durationMs, TimelinePolicy policy) {
        if (durationMs <= 0L) {
            return List.of();
        }

        long bucketMs = policy.focusBucket().toMillis();
        long requiredMs = (long) (bucketMs * policy.focusCoverageFloor());

        List<FocusBucket> buckets = new ArrayList<>();
        for (long start = 0L; start < durationMs; start += bucketMs) {
            List<Byte> levels = replay.slotsStartingIn(start, start + bucketMs).stream()
                    .map(slot -> slot.outcome().engagementLevel().orElse(null))
                    .filter(Objects::nonNull)
                    .toList();

            // 게이트는 4단계 판정이 덮은 시간으로 잰다. 마지막 자투리 칸도 30초 기준으로 재므로 사실상 항상
            // null 이다 — 짧은 칸에 맞춰 게이트를 줄이면 판정 1건짜리 구간이 만점으로 올라온다(§2.8).
            Double level = levels.size() * SLOT_MS < requiredMs ? null : average(levels);
            buckets.add(new FocusBucket(start / 1000L, level));
        }
        return List.copyOf(buckets);
    }

    private static Double average(List<Byte> levels) {
        double sum = 0d;
        for (Byte level : levels) {
            sum += level;
        }
        return FocusLevels.roundToTwo(sum / levels.size());
    }
}
