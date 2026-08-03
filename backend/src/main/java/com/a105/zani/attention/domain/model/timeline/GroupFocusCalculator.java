package com.a105.zani.attention.domain.model.timeline;

import java.util.ArrayList;
import java.util.List;

/**
 * 학생별 30초 칸 값을 모아 같은 칸끼리 평균한다. 강사 응답의 주 계열이다(설계 문서 §2.10).
 *
 * <p>값이 {@code null} 인 학생은 평균에서 빼고 1단계로 채우지 않는다. 채우면 카메라를 끈 학생이 집단 평균을 끌어내린다.
 *
 * <p>숨김 판정은 {@code eligibleCount} 로 한다 — 집계 대상이 28명인데 게이트를 통과한 학생이 3명뿐인 경우는 감추지 않는다. 5초 신호 점을 함께 받는 이유가 이 인원수 하나 때문이며,
 * 30초 칸의 인원은 그 칸에 걸친 스냅샷들의 <b>최솟값</b>이다.
 */
public final class GroupFocusCalculator {

    private GroupFocusCalculator() {}

    public static List<GroupFocusBucket> calculate(
            List<ParticipantReplay> replays, List<GroupSignalPoint> signals, long durationMs, TimelinePolicy policy) {
        if (replays.isEmpty() || durationMs <= 0L) {
            return List.of();
        }

        long bucketSeconds = policy.focusBucket().toSeconds();

        // 학생별 칸 값을 먼저 다 만든다. 칸 인덱스가 같으면 같은 시각이다.
        List<List<FocusBucket>> perStudent = replays.stream()
                .map(replay -> FocusFlowCalculator.calculate(replay, durationMs, policy))
                .toList();

        int bucketCount = perStudent.getFirst().size();
        List<GroupFocusBucket> buckets = new ArrayList<>();
        for (int i = 0; i < bucketCount; i++) {
            long startSeconds = i * bucketSeconds;
            int eligible = minimumEligibleIn(signals, startSeconds, startSeconds + bucketSeconds);

            Double level = null;
            if (eligible >= policy.minimumEligible()) {
                List<Double> values = new ArrayList<>();
                for (List<FocusBucket> student : perStudent) {
                    Double value = student.get(i).focusLevel();
                    if (value != null) {
                        values.add(value);
                    }
                }
                level = values.isEmpty()
                        ? null
                        : FocusLevels.roundToTwo(values.stream()
                                .mapToDouble(Double::doubleValue)
                                .average()
                                .orElseThrow());
            }
            buckets.add(new GroupFocusBucket(startSeconds, level, eligible));
        }
        return List.copyOf(buckets);
    }

    /**
     * 칸에 걸친 5초 스냅샷들의 {@code eligibleCount} 최솟값.
     *
     * <p>해당하는 점이 없으면 0 이다 — 인원을 모르는 칸은 감추는 쪽으로 기운다.
     */
    private static int minimumEligibleIn(List<GroupSignalPoint> signals, long fromSeconds, long toSeconds) {
        return signals.stream()
                .filter(point -> point.offsetSeconds() >= fromSeconds && point.offsetSeconds() < toSeconds)
                .mapToInt(GroupSignalPoint::eligibleCount)
                .min()
                .orElse(0);
    }
}
