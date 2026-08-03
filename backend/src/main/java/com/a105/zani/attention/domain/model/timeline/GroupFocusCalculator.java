package com.a105.zani.attention.domain.model.timeline;

import java.util.ArrayList;
import java.util.List;

/**
 * 학생별 30초 칸 값을 모아 같은 칸끼리 평균한다. 강사 응답의 주 계열이다(설계 문서 §2.10).
 *
 * <p>평균에 들어가는 것은 <b>그 칸의 집계 대상</b> 학생의 값뿐이다. 값이 {@code null} 인 학생은 평균에서 빼고 1단계로 채우지 않는다 — 채우면 카메라를 끈 학생이 집단 평균을 끌어내린다.
 *
 * <p>숨김 판정은 {@code eligibleCount} 로 한다 — 집계 대상이 28명인데 게이트를 통과한 학생이 3명뿐인 경우는 감추지 않는다. 5초 신호 점을 함께 받는 이유가 이 인원수 하나 때문이며,
 * 30초 칸의 인원은 그 칸에 걸친 스냅샷들의 <b>최솟값</b>이다.
 *
 * <p><b>알고 남긴 위험</b>: 기여자 수에는 최소 인원 기준을 걸지 않으므로 집계 대상 28명 중 기여자가 1명인 칸은 사실상 그 한 학생의 값이 집단 평균으로 노출된다. 지라 문구·완료 조건이
 * {@code eligibleCount} 만 보라고 정했고, 70% 게이트가 이미 빡빡해 기여자 수에까지 기준을 걸면 강사 그래프 공백이 크게 늘어난다. 실측에서 기여자 1~2명 칸이 잦으면 다시 논의한다(설계
 * 문서 §2.10 의 "알고 남긴 위험" 에 논의 기록이 있다).
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
            List<GroupSignalPoint> covering = pointsIn(signals, startSeconds, startSeconds + bucketSeconds);
            int eligible = minimumEligibleIn(covering);

            Double level = null;
            if (eligible >= policy.minimumEligible()) {
                List<Double> values = new ArrayList<>();
                for (int student = 0; student < replays.size(); student++) {
                    Double value = perStudent.get(student).get(i).focusLevel();
                    if (value != null && eligibleThroughout(replays.get(student), covering)) {
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

    /** 칸에 걸친 5초 스냅샷. {@code eligibleCount} 와 기여자 판정이 같은 점 목록을 봐야 분자가 분모의 부분집합이 된다. */
    private static List<GroupSignalPoint> pointsIn(List<GroupSignalPoint> signals, long fromSeconds, long toSeconds) {
        return signals.stream()
                .filter(point -> point.offsetSeconds() >= fromSeconds && point.offsetSeconds() < toSeconds)
                .toList();
    }

    /**
     * 칸에 걸친 5초 스냅샷들의 {@code eligibleCount} 최솟값.
     *
     * <p>해당하는 점이 없으면 0 이다 — 인원을 모르는 칸은 감추는 쪽으로 기운다.
     */
    private static int minimumEligibleIn(List<GroupSignalPoint> covering) {
        return covering.stream().mapToInt(GroupSignalPoint::eligibleCount).min().orElse(0);
    }

    /**
     * 칸에 걸친 <b>모든</b> 5초 스냅샷에서 집계 대상이었던 학생인지.
     *
     * <p>한 스냅샷이라도 접속 1분을 못 넘겼거나 측정 불가 1분 지속으로 분모에서 빠져 있었으면 그 칸 전체에서 뺀다. {@code eligibleCount} 를 칸의 최솟값으로 잡은 것과 같은
     * 보수성이며, 이렇게 해야 분자에 기여한 학생 집합이 분모가 센 모집단의 부분집합이 된다 — 그러지 않으면 두 값이 서로 다른 모집단을 보게 된다(설계 문서 §2.10).
     */
    private static boolean eligibleThroughout(ParticipantReplay replay, List<GroupSignalPoint> covering) {
        for (GroupSignalPoint point : covering) {
            long atMs = point.offsetSeconds() * 1000L;
            if (!replay.counted(atMs) || replay.measurementSuspended(atMs)) {
                return false;
            }
        }
        return true;
    }
}
