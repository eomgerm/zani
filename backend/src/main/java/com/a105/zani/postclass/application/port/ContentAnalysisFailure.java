package com.a105.zani.postclass.application.port;

/**
 * 공통 분석이 실패한 이유. <b>다시 시도하면 결과가 달라질 수 있는가</b>로 가른다.
 *
 * <p>{@code PostClassRetryPolicy} 는 재시도 여부를 단계를 수행하는 쪽이 정하라고 요구한다 — "GMS 호출 한도 초과는 기다리면 풀리지만 구조화 스키마 위반은 몇 번을 보내도 같다".
 * 두 실패를 한 값으로 합치면 그 판단을 할 수 없고, {@code temperature: 0} 이라 같은 요청에는 같은 응답이 오므로 재시도 5회와 GMS 호출 5회를 헛되이 쓴다.
 */
public enum ContentAnalysisFailure {
    /** timeout·401·402·429·5xx 처럼 호출 자체가 안 된 경우. 기다리면 풀릴 수 있다. */
    UNAVAILABLE,

    /**
     * 응답은 왔지만 쓸 수 없고, 같은 요청에는 같은 응답이 온다. 스키마 위반, 모델 거부, 출력 상한에 걸린 절단({@code finish_reason=length})이 여기 든다 — 절단은 상한을 올리지
     * 않는 한 재시도해도 같은 지점에서 잘린다.
     */
    UNUSABLE_RESPONSE,

    /**
     * 전사를 한 줄까지 줄여도 요청이 게이트웨이 본문 상한에 들어가지 않는다. 보내면 게이트웨이가 본문을 잘라 "Model not found" 로 답해(가이드 §4.1) 원인을 알 수 없는 실패가 되므로 아예
     * 보내지 않는다. 전사가 그대로인 한 재시도해도 같다.
     */
    REQUEST_TOO_LARGE
}
