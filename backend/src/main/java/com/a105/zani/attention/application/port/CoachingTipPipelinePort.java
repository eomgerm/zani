package com.a105.zani.attention.application.port;

/**
 * 트리거가 열렸을 때 전사 → 팁 생성 → 저장을 이어받는 포트.
 *
 * <p>구현은 coach 오케스트레이션(티켓 204)이 갖는다. 트리거 판정은 이 호출을 기다리지 않는다 — 전사 20초와 LLM 6초를 폴링 응답 안에서 기다리면 강사의 10초 주기가 26초 동안 막힌다.
 *
 * <p>결과는 {@link CoachingTriggerStatePort#completeOutcome} 로 돌아온다. 강사는 다음 폴링에서 그것을 받아 간다.
 */
public interface CoachingTipPipelinePort {

    /** 즉시 반환해야 한다. 전사·LLM 은 호출자 스레드에서 수행하지 않는다. */
    void start(CoachingTipRequest request);
}
