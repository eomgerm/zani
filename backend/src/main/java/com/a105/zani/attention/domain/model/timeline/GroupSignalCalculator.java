package com.a105.zani.attention.domain.model.timeline;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.a105.zani.attention.domain.model.AttentionState;

/**
 * 참가자 재생기 여러 개에서 5초 격자마다 익명 집단 신호 비율을 뽑는다.
 *
 * <p>30초 집중 흐름({@link GroupFocusCalculator})과 격자가 다르다. 이름을 "타임라인" 이 아니라 "신호" 로 두는 이유이며, 이쪽이 5초 격자를 가리킨다는 것을 호출부에서 바로 읽을
 * 수 있어야 한다.
 *
 * <p>실시간 코칭 트리거({@code GetCoachingSignalsService})와 판정 규칙은 같지만 파이프라인은 공유하지 않는다. 실시간은 Redis 상태를 읽고 이쪽은 저장된 원본 행을 재생하며,
 * 동작 중인 코칭 트리거에 회귀를 만들지 않기 위해 중복이 남더라도 갈라 둔다.
 */
public final class GroupSignalCalculator {

    private GroupSignalCalculator() {}

    public static List<GroupSignalPoint> calculate(
            List<ParticipantReplay> replays, long durationMs, TimelinePolicy policy) {
        long step = policy.samplingInterval().toMillis();
        List<GroupSignalPoint> points = new ArrayList<>();
        for (long at = 0; at <= durationMs; at += step) {
            points.add(pointAt(replays, at, policy));
        }
        return List.copyOf(points);
    }

    private static GroupSignalPoint pointAt(List<ParticipantReplay> replays, long atMs, TimelinePolicy policy) {
        long offsetSeconds = atMs / 1000L;

        List<ParticipantReplay> connected =
                replays.stream().filter(replay -> replay.counted(atMs)).toList();
        List<ParticipantReplay> eligible = connected.stream()
                .filter(replay -> !replay.measurementSuspended(atMs))
                .toList();
        long cameraOffCount =
                connected.stream().filter(replay -> replay.cameraOff(atMs)).count();

        // 카메라 OFF 비율의 분모는 제외 전 접속자 전체다. 확인 필요 비율과 같은 분모를 쓰면 분자에만 있고
        // 분모에 없는 값이 되어 계산이 성립하지 않는다(설계 문서 §2.3).
        Double cameraOffRatio =
                connected.size() < policy.minimumEligible() ? null : (double) cameraOffCount / connected.size();

        if (eligible.size() < policy.minimumEligible()) {
            return GroupSignalPoint.withoutDistribution(
                    offsetSeconds, connected.size(), eligible.size(), cameraOffRatio);
        }

        Map<AttentionState, Integer> perState = new EnumMap<>(AttentionState.class);
        int checkNeededCount = 0;
        for (ParticipantReplay replay : eligible) {
            Set<AttentionState> states = replay.significantStatesAt(atMs);
            if (states.isEmpty()) {
                continue;
            }
            // 분자는 학생 단위 합집합이다. 상태별로 세어 더하면 두 상태를 겪은 학생이 두 번 세어져 비율이 부푼다.
            checkNeededCount++;
            for (AttentionState state : states) {
                perState.merge(state, 1, Integer::sum);
            }
        }

        int denominator = eligible.size();
        return new GroupSignalPoint(
                offsetSeconds,
                connected.size(),
                denominator,
                (double) checkNeededCount / denominator,
                cameraOffRatio,
                ratioOf(perState, AttentionState.CONFUSED, denominator),
                ratioOf(perState, AttentionState.MISSED, denominator),
                ratioOf(perState, AttentionState.NON_RESPONSE, denominator),
                ratioOf(perState, AttentionState.UNMEASURABLE, denominator));
    }

    /**
     * 상태별 학생 수 ÷ 집계 대상 수.
     *
     * <p>한 학생이 두 상태를 가지면 두 분포 모두에 들어간다. 그래서 분포 4종의 합이 확인 필요 비율보다 클 수 있고, 그것이 정상이다.
     */
    private static double ratioOf(Map<AttentionState, Integer> perState, AttentionState state, int denominator) {
        return (double) perState.getOrDefault(state, 0) / denominator;
    }
}
