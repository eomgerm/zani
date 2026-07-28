package com.a105.zani.audioclip.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 강사 코칭 오디오 설정.
 *
 * @param window 링버퍼가 유지하는 최근 구간 길이이자 전사에 넘길 기본 구간
 * @param minTranscribable 이보다 짧게 확보됐으면 전사를 시도하지 않는다
 * @param sampleRate Egress WebSocket 출력의 샘플레이트. LiveKit 은 들어오는 트랙을 따르며 보통 48kHz 다
 * @param maxSessions 버퍼를 동시에 들 수 있는 세션 수. 세션당 window×sampleRate×2바이트를 차지하므로 상한이 없으면 컨테이너가 OOM 으로 죽어 진행 중인 모든 강의가 끊긴다
 * @param streamSecret Egress 만 아는 공유 시크릿. 내부 WebSocket 경로 인증에 쓴다(주소 구성은 recording 소관)
 */
@ConfigurationProperties(prefix = "audio-clip")
public record AudioClipProperties(
        Duration window, Duration minTranscribable, Integer sampleRate, Integer maxSessions, String streamSecret) {

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
        if (maxSessions == null || maxSessions <= 0) {
            // 48kHz 5분 = 세션당 약 29MB. 8개면 약 230MB로, 동시 강의 수와 힙 여유 사이의 기본 절충이다.
            maxSessions = 8;
        }
    }
}
