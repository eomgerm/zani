package com.a105.zani.coach.application.generatetip;

import java.util.List;
import java.util.Optional;

import com.a105.zani.attention.application.port.CoachingTipType;
import com.a105.zani.coach.domain.model.CoachingTipRatios;

/**
 * 비율로 팁 유형을 고른다(기준 문서 §7.6). (S15P11A105-204)
 *
 * <p>85 가 유형 enum 을 폴링 응답 계약으로 먼저 확정했고, 어느 유형을 고를지는 이 티켓이 정한다. enum 만 가져다 쓰고 선택 규칙은 여기 둔다 — §8 문구 템플릿과 같은 곳에 있어야 §7.6 을
 * 고칠 때 문구와 함께 보인다.
 *
 * <p>79 의 {@code CoachingSignalSummary#dominantState()} 를 쓰지 못하는 이유: 85 의 {@code CoachingTipRequest} 가 상태별 비율을
 * {@code double} 4개로 펼쳐 넘긴다. 집계 객체가 아니라 값만 오므로 최다 선택과 동률 우선순위를 여기서 다시 판단한다. 순서는 79 의 {@code TIE_BREAK_ORDER} 와 같아야 한다.
 *
 * <p>계산 자체는 외부 의존이 없지만 85 가 소유한 {@code CoachingTipType} 을 돌려주므로 application 계층에 둔다. 도메인에 두면 coach 의 domain 이 타 도메인
 * application 계층을 import 하게 된다. coach 전용 enum 을 따로 만들어 매핑하는 방법도 있지만, 85 가 유형을 늘릴 때 조용히 어긋나는 평행 계약이 생긴다.
 */
public final class CoachingTipTypeSelector {

    /** 복합 팁의 하한(§7.6-2). 헷갈림·놓침이 각각 이 비율을 <b>넘어야</b> 한다. */
    private static final double BOTH_HIGH_THRESHOLD = 0.20;

    /**
     * 상태별 비율이 같을 때 고를 순서(§7.6-4).
     *
     * <p>79 의 {@code CoachingSignalSummary.TIE_BREAK_ORDER} 와 순서가 같아야 한다. 결정론적 순서가 없으면 비율이 같을 때마다 팁이 달라져 강사가 보기에 이유 없이
     * 조언이 바뀐다.
     */
    private static final List<CoachingTipType> TIE_BREAK_ORDER = List.of(
            CoachingTipType.CONFUSED,
            CoachingTipType.MISSED,
            CoachingTipType.NON_RESPONSE,
            CoachingTipType.UNMEASURABLE);

    private CoachingTipTypeSelector() {}

    /** 팁 유형을 고른다. 분모가 0이거나 유의 상태를 겪은 학생이 없으면 비어 있다. */
    public static Optional<CoachingTipType> select(CoachingTipRatios ratios) {
        if (ratios.isEmpty()) {
            return Optional.empty();
        }
        if (isBothHigh(ratios)) {
            return Optional.of(CoachingTipType.CONFUSED_AND_MISSED);
        }
        return dominant(ratios);
    }

    /**
     * 헷갈림과 놓침이 각각 20%를 넘고, 둘의 합이 나머지 두 유형보다 큰가(§7.6-2).
     *
     * <p>"둘의 합" 조건이 붙은 이유: 조건이 "각각 20% 초과"뿐이면 헷갈림 21%·놓침 21%·무응답 60% 일 때도 복합 팁이 떠서 정작 가장 큰 문제를 가린다. 복합 팁은 헷갈림과 놓침이 실제로
     * 지배적일 때만 뜬다.
     */
    private static boolean isBothHigh(CoachingTipRatios ratios) {
        if (ratios.confusedRatio() <= BOTH_HIGH_THRESHOLD || ratios.missedRatio() <= BOTH_HIGH_THRESHOLD) {
            return false;
        }
        return ratios.confusedRatio() + ratios.missedRatio() > ratios.nonResponseRatio() + ratios.unmeasurableRatio();
    }

    /** 가장 높은 비율의 유형(§7.6-3). 같으면 {@link #TIE_BREAK_ORDER} 순으로 고른다(§7.6-4). */
    private static Optional<CoachingTipType> dominant(CoachingTipRatios ratios) {
        return TIE_BREAK_ORDER.stream()
                .filter(type -> ratioOf(ratios, type) > 0)
                .reduce((left, right) -> ratioOf(ratios, right) > ratioOf(ratios, left) ? right : left);
    }

    static double ratioOf(CoachingTipRatios ratios, CoachingTipType type) {
        return switch (type) {
            case CONFUSED -> ratios.confusedRatio();
            case MISSED -> ratios.missedRatio();
            case NON_RESPONSE -> ratios.nonResponseRatio();
            case UNMEASURABLE -> ratios.unmeasurableRatio();
            // 복합 팁은 단일 상태에 대응하지 않는다. 첫 줄은 유의 학생 비율을 쓴다(§8).
            case CONFUSED_AND_MISSED -> ratios.significantRatio();
        };
    }
}
