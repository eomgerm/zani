package com.a105.zani.attention.domain.model.timeline;

/**
 * 5초 격자 한 점의 익명 집단 집계.
 *
 * <p>학생 식별자와 학생별 값은 어떤 필드로도 담지 않는다(REPORT-I-002 · ALERT-004).
 *
 * <p>모든 비율은 0.0~1.0 분수다. {@code null} 은 값 없음이며 절대 0 이 아니다 — 인원이 모자라 감춘 구간을 0% 로 그리면 "집중이 회복됐다"는 거짓 신호가 된다.
 *
 * @param offsetSeconds 세션 시작 기준 경과 초
 * @param connectedCount 접속 1분을 넘긴 학생 수. 제외 전이다
 * @param eligibleCount 위에서 측정 불가 1분 지속자를 뺀 수
 * @param checkNeededRatio 확인 필요 비율. 분모는 {@code eligibleCount} 다
 * @param cameraOffRatio 카메라 OFF 비율. 분모는 {@code connectedCount} 로 위와 <b>다르다</b>. 두 값을 더하거나 비교하면 안 된다
 * @param confusedRatio 헷갈려요 비율. 분모는 {@code eligibleCount}
 * @param missedRatio 놓쳤어요 비율. 분모는 {@code eligibleCount}
 * @param nonResponseRatio 무응답 비율. 분모는 {@code eligibleCount}
 * @param unmeasurableRatio 판단 불가 비율. 분모는 {@code eligibleCount}
 */
public record GroupTimelinePoint(
        long offsetSeconds,
        int connectedCount,
        int eligibleCount,
        Double checkNeededRatio,
        Double cameraOffRatio,
        Double confusedRatio,
        Double missedRatio,
        Double nonResponseRatio,
        Double unmeasurableRatio) {

    /**
     * 확인 필요 비율과 응답 분포만 감춘 점.
     *
     * <p>카메라 OFF 비율은 분모가 달라 함께 감추지 않는다. 집계 대상이 4명이어도 접속자가 10명이면 "10명 중 6명이 카메라를 껐다"는 사실은 익명성을 해치지 않고 그대로 말할 수 있다.
     */
    public static GroupTimelinePoint withoutDistribution(
            long offsetSeconds, int connectedCount, int eligibleCount, Double cameraOffRatio) {
        return new GroupTimelinePoint(
                offsetSeconds, connectedCount, eligibleCount, null, cameraOffRatio, null, null, null, null);
    }
}
