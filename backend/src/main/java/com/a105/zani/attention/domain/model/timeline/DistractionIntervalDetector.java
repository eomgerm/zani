package com.a105.zani.attention.domain.model.timeline;

import java.util.ArrayList;
import java.util.List;

/**
 * 확인 필요 비율 시계열에서 흐트러짐 구간을 찾는다(FRD §18.3).
 *
 * <p>누적은 <b>임계 조건을 만족한 점이 덮는 시간</b>이다. 첫 점과 마지막 점의 시각 차이가 아니다 — 비율이 {@code null} 인 점을 건너뛰면서 시각 차이로 재면 판단하지 못한 시간까지 지속
 * 시간에 들어간다.
 *
 * <p>여는 조건은 초과, 닫는 조건은 이상이다. 어긋나 보이지만 의도한 것이다. 구간을 표시하면 강사에게 "여기서 놓쳤다"고 말하는 셈이라 여는 쪽을 엄격히 두고, 반대로 회복을 늦게 인정할 이유는 없다.
 */
public final class DistractionIntervalDetector {

    private DistractionIntervalDetector() {}

    public static List<DistractionInterval> detect(List<GroupSignalPoint> points, TimelinePolicy policy) {
        if (points.isEmpty()) {
            return List.of();
        }
        long step = policy.samplingInterval().toSeconds();
        long startHold = policy.distractionStartHold().toSeconds();
        long endHold = policy.distractionEndHold().toSeconds();

        List<DistractionInterval> intervals = new ArrayList<>();
        boolean open = false;
        long openedAt = 0L;
        long aboveRunStart = 0L;
        long aboveHeld = 0L;
        long belowRunStart = 0L;
        long belowHeld = 0L;
        boolean sawRatio = false;
        long lastRatioAt = 0L;

        for (GroupSignalPoint point : points) {
            Double ratio = point.checkNeededRatio();
            if (ratio == null) {
                // 판단 불가다. 진행 중인 구간을 끝내지도, 어느 누적을 늘리지도 않는다(설계 문서 §2.5).
                // 0% 로 보면 인원이 잠깐 모자랐던 것이 "집중이 회복됐다"는 거짓 신호가 된다.
                continue;
            }
            sawRatio = true;
            lastRatioAt = point.offsetSeconds();
            if (!open) {
                if (ratio >= policy.distractionStartRatio()) {
                    if (aboveHeld == 0L) {
                        aboveRunStart = point.offsetSeconds();
                    }
                    aboveHeld += step;
                    if (aboveHeld > startHold) {
                        open = true;
                        openedAt = aboveRunStart;
                        belowHeld = 0L;
                    }
                } else {
                    aboveHeld = 0L;
                }
            } else {
                if (ratio < policy.distractionEndRatio()) {
                    if (belowHeld == 0L) {
                        belowRunStart = point.offsetSeconds();
                    }
                    belowHeld += step;
                    if (belowHeld >= endHold) {
                        intervals.add(new DistractionInterval(openedAt, belowRunStart));
                        open = false;
                        aboveHeld = 0L;
                        belowHeld = 0L;
                    }
                } else {
                    belowHeld = 0L;
                }
            }
        }
        // 열린 채 끝나면 마지막 점이 아니라 마지막으로 비율이 있었던 점에서 닫는다. 시계열 끝이 판단 불가로
        // 채워져 있을 때 목록의 마지막 점을 쓰면 판단하지 못한 시간까지 지속 시간에 들어가, 위 §2.5 원칙이
        // 열린 채 끝나는 경로에서만 깨진다.
        //
        // 유효한 점이 없었으면(전부 null) 구간은 길이 0 이 된다. 덮는 시간이 없으므로 만들지 않는다 —
        // StateIntervalMerger 가 길이 0 상태 구간을 버리는 것과 같은 판단이다.
        if (open && sawRatio && lastRatioAt > openedAt) {
            intervals.add(new DistractionInterval(openedAt, lastRatioAt));
        }
        return mergeAdjacent(intervals, policy);
    }

    /**
     * 간격이 병합 기준보다 짧은 이웃 구간을 하나로 합친다.
     *
     * <p>{@code detect} 가 마지막에 부르는 단계를 그대로 노출한다. 병합 경계를 확인하려고 시계열을 억지로 꾸미지 않아도 되게 하려는 것이다.
     */
    public static List<DistractionInterval> mergeAdjacent(List<DistractionInterval> intervals, TimelinePolicy policy) {
        if (intervals.size() < 2) {
            return List.copyOf(intervals);
        }
        long mergeGap = policy.distractionMergeGap().toSeconds();

        List<DistractionInterval> merged = new ArrayList<>();
        DistractionInterval current = intervals.getFirst();
        for (DistractionInterval next : intervals.subList(1, intervals.size())) {
            if (next.startSeconds() < current.endSeconds() + mergeGap) {
                current = new DistractionInterval(
                        current.startSeconds(), Math.max(current.endSeconds(), next.endSeconds()));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return List.copyOf(merged);
    }
}
