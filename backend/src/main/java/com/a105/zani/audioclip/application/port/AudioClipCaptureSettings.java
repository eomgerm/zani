package com.a105.zani.audioclip.application.port;

import java.time.Duration;

/**
 * 클립 캡처에 적용할 구간 설정.
 *
 * <p>값의 출처는 외부 설정이지만 application 계층이 스프링 설정 타입을 알 필요는 없다. infrastructure 가 설정을 읽어 이 타입으로 바꿔 주입한다(세션 도메인의
 * {@code MediaServerCredentials} 와 같은 방식).
 *
 * @param window 전사에 넘길 구간 길이. 링버퍼가 유지하는 길이보다 길게 요청하면 확보된 만큼만 담긴다
 * @param minTranscribable 이보다 짧게 확보됐으면 전사를 시도하지 않는다. 수업 시작 직후처럼 맥락이 부족한 구간에 전사 비용을 쓰지 않기 위한 하한이다
 */
public record AudioClipCaptureSettings(Duration window, Duration minTranscribable) {

    public AudioClipCaptureSettings {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive: " + window);
        }
        if (minTranscribable == null || minTranscribable.isNegative()) {
            throw new IllegalArgumentException("minTranscribable must not be negative: " + minTranscribable);
        }
        if (minTranscribable.compareTo(window) > 0) {
            // 하한이 창보다 길면 버퍼가 가득 차도 조건을 만족할 수 없어 전사가 영구히 일어나지 않는다.
            throw new IllegalArgumentException(
                    "minTranscribable must not exceed window: " + minTranscribable + " > " + window);
        }
    }
}
