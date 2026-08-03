package com.a105.zani.attention.domain.model.timeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.DetectorOutcome;

/**
 * 학생 상태를 5초 격자마다 하나 고른 뒤 인접 동일 상태를 병합한다(설계 문서 §2.13).
 *
 * <p>집중 흐름이 30초로 굵어지면서 상태와 격자를 공유할 수 없게 됐다. 서버가 병합해 내려보내고 FE 는 받은 대로 그린다 — 클라이언트가 판단을 만들어 내지 않게 한다.
 *
 * <p>{@code GOOD} 도 구간으로 준다. 셋만 주면 나머지 시간이 비고 FE 가 그 빈 곳을 {@code GOOD} 으로 채워야 하는데, 그것이 곧 클라이언트가 판단을 만드는 일이다.
 *
 * <p>어디에도 걸리지 않는 시각은 구간을 만들지 않는다. 구간과 구간 사이가 빌 수 있고, FE 가 그 자리를 "기록 없음" 으로 그린다.
 */
public final class StateIntervalMerger {

    private StateIntervalMerger() {}

    public static List<StateInterval> merge(ParticipantReplay replay, long durationMs, TimelinePolicy policy) {
        long step = policy.samplingInterval().toMillis();
        long stepSeconds = policy.samplingInterval().toSeconds();
        // 격자의 마지막 표본은 세션이 끝나는 순간에 찍히므로, 거기에 격자 한 칸을 더하면 구간이 세션 밖으로
        // 나간다. 35초 세션이 [35,40) 을 내놓으면 FE 는 축에 없는 시간을 그려야 한다.
        long durationSeconds = durationMs / 1000L;

        List<StateInterval> intervals = new ArrayList<>();
        StudentTimelineState openState = null;
        long openStart = 0L;
        long openEnd = 0L;

        for (long at = 0L; at <= durationMs; at += step) {
            StudentTimelineState state = stateAt(replay, at);
            if (state == openState) {
                if (state != null) {
                    openEnd = Math.min(at / 1000L + stepSeconds, durationSeconds);
                }
                continue;
            }
            if (openState != null && openEnd > openStart) {
                intervals.add(new StateInterval(openStart, openEnd, openState));
            }
            openState = state;
            openStart = at / 1000L;
            openEnd = Math.min(at / 1000L + stepSeconds, durationSeconds);
        }
        // 세션이 끝나는 순간에 상태가 바뀌면 길이 0 짜리 구간이 열린다. 덮는 시간이 없으므로 버린다.
        if (openState != null && openEnd > openStart) {
            intervals.add(new StateInterval(openStart, openEnd, openState));
        }
        return List.copyOf(intervals);
    }

    /**
     * 그 시각의 상태 하나. 우선순위는 {@code CAMERA_OFF} → {@code UNMEASURABLE} → {@code CHECK_NEEDED} → {@code GOOD} 이다.
     *
     * <p>측정할 수 없었던 사유를 먼저 말한다. 카메라가 꺼져 있던 시간을 "확인 필요" 로 보여주면 학생이 고칠 수 없는 것을 고치라는 말이 된다.
     *
     * <p>카메라 OFF·검출기 불능은 관측 그 자체로 판단한다. 분모 제외처럼 1분을 기다리면 30초짜리 카메라 OFF 가 "기록 없음" 으로 사라져, 관측이 있었던 시간이 없었던 것처럼 보인다.
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
            // 참여 상태를 만들지 않는 값이지만 학생에게는 "측정하지 못했다" 가 사실 그대로다.
            return StudentTimelineState.UNMEASURABLE;
        }

        Set<AttentionState> significant = replay.significantStatesAt(atMs);
        if (significant.contains(AttentionState.UNMEASURABLE)) {
            return StudentTimelineState.UNMEASURABLE;
        }
        if (!significant.isEmpty()) {
            return StudentTimelineState.CHECK_NEEDED;
        }
        return replay.good(atMs) ? StudentTimelineState.GOOD : null;
    }
}
