package com.a105.zani.coach.application.port;

import java.time.Duration;

/**
 * 팁 생성에 적용할 값. (S15P11A105-204)
 *
 * <p>값의 출처는 외부 설정이지만 application 계층이 스프링 설정 타입을 알 필요는 없다. infrastructure 가 설정을 읽어 이 타입으로 바꿔 주입한다 — audioclip 의
 * {@code AudioClipCaptureSettings}, session 의 {@code MediaServerCredentials} 와 같은 방식이다.
 *
 * @param minConfidence 이 값에 못 미치는 개념은 팁을 만들지 않는다. 강사에게 틀린 개념을 짚어주는 것이 팁을 건너뛰는 것보다 나쁘다
 * @param transcriptTailChars 프롬프트에 넣을 전사 마지막 글자 수 — window 한 번 분량의 상한이다
 * @param maxTriggerDelay 트리거 시각부터 작업 시작까지 허용하는 지연. 이보다 밀렸으면 전사·GMS 를 호출하지 않는다 — 지금 오디오를 떠도 트리거 당시가 아닌 발화가 섞인다
 */
public record CoachingTipSettings(double minConfidence, int transcriptTailChars, Duration maxTriggerDelay) {

    public CoachingTipSettings {
        if (minConfidence < 0 || minConfidence > 1) {
            throw new IllegalArgumentException("minConfidence must be between 0 and 1: " + minConfidence);
        }
        if (transcriptTailChars <= 0) {
            throw new IllegalArgumentException("transcriptTailChars must be positive: " + transcriptTailChars);
        }
        if (maxTriggerDelay == null || maxTriggerDelay.isNegative() || maxTriggerDelay.isZero()) {
            throw new IllegalArgumentException("maxTriggerDelay must be positive: " + maxTriggerDelay);
        }
    }
}
