package com.a105.zani.attention.domain.model;

/**
 * 브라우저 검출기가 낸 관측 한 건의 판정 신호.
 *
 * @param outcome 검출기 출력 7종 중 하나(§1)
 * @param lowEngagement 저참여인지. 4단계 출력에만 의미가 있다
 */
public record DetectionSignal(DetectorOutcome outcome, boolean lowEngagement) {

    /**
     * 저참여 여부는 <b>단계값이 아니라 확률 합으로</b> 정해진다(§3.3).
     *
     * <p>예를 들어 {@code 1단계 0.20 / 2단계 0.15 / 3단계 0.60 / 4단계 0.05} 는 가장 높은 값이 3단계지만 아래 두 단계의 합이 0.35 라 저참여다. 모델이 "3단계 같긴
     * 한데 확신은 없다"고 말하는 중이다. 그래서 서버가 단계값만 보고 되짚을 수 없고, 브라우저가 계산한 결과를 함께 받는다. 확률 네 개는 받지 않는다 — 원본 영상을 보관하지 않아 모델 개선에 쓸 수
     * 없다.
     */
    public boolean countsAsLowEngagement() {
        return outcome.engagementLevel().isPresent() && lowEngagement;
    }
}
