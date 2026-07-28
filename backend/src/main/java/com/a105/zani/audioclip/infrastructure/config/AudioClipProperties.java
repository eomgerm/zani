package com.a105.zani.audioclip.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 강사 코칭 오디오 설정.
 *
 * @param window 링버퍼가 유지하는 최근 구간 길이
 * @param minTranscribable 이보다 짧게 확보됐으면 전사를 시도하지 않는다
 * @param sampleRate Egress WebSocket 출력의 샘플레이트. LiveKit 은 들어오는 트랙을 따르며 보통 48kHz 다
 * @param streamSecret Egress 만 아는 공유 시크릿. 내부 WebSocket 경로 인증에 쓴다
 * @param streamUrlTemplate Egress 에 넘길 수신 주소. {sessionId}·{secret} 자리표시자를 치환한다
 */
@ConfigurationProperties(prefix = "audio-clip")
public record AudioClipProperties(
        Duration window, Duration minTranscribable, Integer sampleRate, String streamSecret, String streamUrlTemplate) {

    public AudioClipProperties {
        if (window == null) {
            window = Duration.ofMinutes(5);
        }
        if (minTranscribable == null) {
            minTranscribable = Duration.ofMinutes(1);
        }
        if (sampleRate == null || sampleRate <= 0) {
            sampleRate = 48_000;
        }
    }

    /** 세션별 Egress 수신 주소. 시크릿이 들어가므로 로그·응답에 남기지 않는다. */
    public String streamUrlFor(long sessionId) {
        if (streamUrlTemplate == null || streamUrlTemplate.isBlank()) {
            throw new IllegalStateException("audio-clip.stream-url-template is not configured");
        }
        return streamUrlTemplate
                .replace("{sessionId}", String.valueOf(sessionId))
                .replace("{secret}", streamSecret == null ? "" : streamSecret);
    }
}
