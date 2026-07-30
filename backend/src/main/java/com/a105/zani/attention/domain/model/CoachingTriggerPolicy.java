package com.a105.zani.attention.domain.model;

import java.time.Duration;
import java.util.OptionalDouble;

/**
 * 코칭 트리거 임계값(확정 문서 §7). 세 값을 한 곳에 모아 둔다.
 *
 * <p>흩어 두면 "30%"가 판정에, "10분"이 저장소에, "60초"가 오디오 모듈에 각각 박혀 한쪽만 바뀐다. 쿨타임은 이 타입이 직접 쓰지 않지만 같은 정책의 일부라 여기서 들고 있다가 저장소에 넘긴다.
 *
 * <p>폐기된 규칙은 담지 않는다 — 추가 5초 유지, 10초 응답 대기, 측정 가능 70% 전제.
 *
 * @param threshold 유의 학생 비율의 하한. 이 값 <b>이상</b>이면 트리거한다
 * @param cooldown 트리거를 연 시각부터 다음 트리거를 막는 기간. 팁 유형과 무관한 단일 글로벌 쿨타임이다
 * @param minimumAudio 확보된 강사 오디오가 이보다 짧으면 트리거하지 않는다
 */
public record CoachingTriggerPolicy(double threshold, Duration cooldown, Duration minimumAudio) {

    public CoachingTriggerPolicy {
        if (!(threshold > 0) || threshold > 1) {
            // 0 이면 학생이 아무 문제 없어도 매번 트리거하고, 1 을 넘으면 영구히 트리거하지 않는다.
            throw new IllegalArgumentException("임계 비율은 0 초과 1 이하여야 합니다: " + threshold);
        }
        if (cooldown == null || !cooldown.isPositive()) {
            throw new IllegalArgumentException("쿨타임은 양수여야 합니다: " + cooldown);
        }
        if (minimumAudio == null || minimumAudio.isNegative()) {
            throw new IllegalArgumentException("오디오 최소 길이는 음수가 될 수 없습니다: " + minimumAudio);
        }
    }

    /**
     * 지금 코칭 파이프라인을 시작할지 판정한다.
     *
     * <p>쿨타임은 여기서 보지 않는다. 쿨타임은 "이미 열린 트리거가 있는가"이고 그 판단은 원자적이어야 해서 저장소가 갖는다. 이 타입이 시각을 받아 비교하면 읽기와 쓰기 사이가 벌어져 동시 폴링 두 건이
     * 모두 통과한다.
     *
     * <p>순서는 보고할 사유의 우선순위다. 분모가 없으면 비율을 말할 수 없고, 비율이 낮으면 오디오를 볼 필요가 없다.
     *
     * @param availableAudio 지금 확보된 강사 오디오 길이
     */
    public CoachingTriggerDecision decide(CoachingSignalSummary summary, Duration availableAudio) {
        OptionalDouble ratio = summary.ratio();
        if (ratio.isEmpty()) {
            return CoachingTriggerDecision.NO_DENOMINATOR;
        }
        if (ratio.getAsDouble() < threshold) {
            return CoachingTriggerDecision.BELOW_THRESHOLD;
        }
        if (availableAudio.compareTo(minimumAudio) < 0) {
            return CoachingTriggerDecision.BUFFER_TOO_SHORT;
        }
        return CoachingTriggerDecision.TRIGGERED;
    }
}
