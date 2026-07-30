package com.a105.zani.coach.domain.model;

/**
 * 팁 유형 선택과 문구 치환에 쓰는 비율 묶음(기준 문서 §7.6·§8). (S15P11A105-204)
 *
 * <p>85 의 {@code CoachingTipRequest} 에서 필요한 값만 옮겨 담는다. 도메인 정책이 트리거 스냅샷 전체(세션·트리거 식별자·시각)를 알 필요가 없고, 그것까지 받으면 순수 계산을
 * 테스트할 때 무의미한 값을 매번 채워야 한다.
 *
 * @param studentsCounted 분모 학생 수(§7.1). 0이면 판단할 학생이 없어 유형을 고르지 않는다
 * @param significantRatio 유의 학생 비율. 중복 제거된 고유 학생 기준이라 상태별 비율의 합과 다를 수 있다. 복합 팁 첫 줄의 "전체 학생의 N%"가 이 값이다
 * @param confusedRatio 헷갈림 비율
 * @param missedRatio 놓침 비율
 * @param nonResponseRatio 무응답 비율
 * @param unmeasurableRatio 자리비움 비율
 */
public record CoachingTipRatios(
        int studentsCounted,
        double significantRatio,
        double confusedRatio,
        double missedRatio,
        double nonResponseRatio,
        double unmeasurableRatio) {

    public CoachingTipRatios {
        if (studentsCounted < 0) {
            throw new IllegalArgumentException("분모 학생 수는 음수가 될 수 없습니다: " + studentsCounted);
        }
    }

    /** 유의 상태를 겪은 학생이 아무도 없는가. 그렇다면 고를 유형이 없다. */
    public boolean isEmpty() {
        return studentsCounted == 0
                || (confusedRatio <= 0 && missedRatio <= 0 && nonResponseRatio <= 0 && unmeasurableRatio <= 0);
    }
}
