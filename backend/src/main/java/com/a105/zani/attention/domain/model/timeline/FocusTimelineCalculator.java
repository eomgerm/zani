package com.a105.zani.attention.domain.model.timeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.DetectorOutcome;

/**
 * 한 학생의 재생기에서 5초 격자마다 집중 점수와 상태를 뽑는다(FRD §19.2).
 *
 * <p>점수는 30초 이동창에서 {@code GOOD} 인 측정 가능 시간 ÷ 전체 측정 가능 시간이다. 측정 가능 시간이 창의 70% 를 못 채우면 값을 내지 않는다 — 몇 초짜리 관측으로 낸 100점은
 * 100점이 아니다.
 *
 * <p>상태는 점수와 달리 창을 보지 않고 그 시각 하나만 본다. 상태 막대는 "지금 무슨 일이 있었나"를 보여주는 것이라 평활화하면 짧은 카메라 OFF 가 사라진다.
 */
public final class FocusTimelineCalculator {

    private FocusTimelineCalculator() {}

    public static List<FocusTimelinePoint> calculate(ParticipantReplay replay, long durationMs, TimelinePolicy policy) {
        long step = policy.samplingInterval().toMillis();
        List<FocusTimelinePoint> points = new ArrayList<>();
        for (long at = 0; at <= durationMs; at += step) {
            points.add(new FocusTimelinePoint(at / 1000L, focusPercentAt(replay, at, policy), stateAt(replay, at)));
        }
        return List.copyOf(points);
    }

    private static Integer focusPercentAt(ParticipantReplay replay, long atMs, TimelinePolicy policy) {
        long window = policy.focusBucket().toMillis();
        long from = atMs - window;

        long measurableMs = 0L;
        long goodMs = 0L;
        for (ObservationSlot slot : replay.slots()) {
            long overlapStart = Math.max(slot.startMs(), from);
            long overlapEnd = Math.min(slot.endMs(), atMs);
            if (overlapEnd <= overlapStart) {
                continue;
            }
            // 겹친 구간의 가운데로 물어본다. 프롬프트 유효 구간이 슬롯 중간에서 시작·종료할 수 있어 슬롯 전체를
            // 한 값으로 보면 경계가 한 슬롯만큼 밀린다.
            long probe = overlapStart + (overlapEnd - overlapStart) / 2;
            if (!replay.measurable(probe)) {
                continue;
            }
            measurableMs += overlapEnd - overlapStart;
            if (replay.good(probe)) {
                goodMs += overlapEnd - overlapStart;
            }
        }

        if (measurableMs < window * policy.focusCoverageFloor()) {
            return null;
        }
        return (int) Math.round(goodMs * 100.0 / measurableMs);
    }

    /**
     * 그 시각의 상태 하나. 우선순위는 {@code CAMERA_OFF} → {@code UNMEASURABLE} → {@code CHECK_NEEDED} → {@code GOOD} 이다.
     *
     * <p>측정할 수 없었던 사유를 먼저 말한다. 카메라가 꺼져 있던 시간을 "확인 필요"로 보여주면 학생이 고칠 수 없는 것을 고치라는 말이 된다.
     */
    private static StudentTimelineState stateAt(ParticipantReplay replay, long atMs) {
        ObservationSlot slot = replay.slotAt(atMs);
        if (slot == null) {
            return null;
        }
        if (slot.outcome() == DetectorOutcome.CAMERA_OFF) {
            return StudentTimelineState.CAMERA_OFF;
        }
        if (slot.outcome() == DetectorOutcome.DETECTOR_UNAVAILABLE) {
            // 참여 상태를 만들지 않는 값이지만 학생에게는 "측정하지 못했다"가 사실 그대로다.
            return StudentTimelineState.UNMEASURABLE;
        }

        Set<AttentionState> states = replay.significantStatesAt(atMs);
        if (states.contains(AttentionState.UNMEASURABLE)) {
            return StudentTimelineState.UNMEASURABLE;
        }
        if (!states.isEmpty()) {
            return StudentTimelineState.CHECK_NEEDED;
        }
        return replay.good(atMs) ? StudentTimelineState.GOOD : null;
    }
}
