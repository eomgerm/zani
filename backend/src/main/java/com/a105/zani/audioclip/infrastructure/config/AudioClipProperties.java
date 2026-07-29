package com.a105.zani.audioclip.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.a105.zani.audioclip.domain.model.PcmAudioFormat;

/**
 * 강사 코칭 오디오 설정.
 *
 * @param window 링버퍼가 유지하는 최근 구간 길이이자 전사에 넘길 기본 구간
 * @param minTranscribable 이보다 짧게 확보됐으면 전사를 시도하지 않는다
 * @param sampleRate Egress WebSocket 출력의 샘플레이트. LiveKit 은 들어오는 트랙을 따르며 보통 48kHz 다
 * @param maxSessions 버퍼를 동시에 들 수 있는 세션 수. 세션당 window×sampleRate×2바이트를 차지하므로 상한이 없으면 컨테이너가 OOM 으로 죽어 진행 중인 모든 강의가 끊긴다
 * @param streamUrlTemplate Egress 가 접속할 수신 주소. Egress 노드에서 도달 가능해야 한다. 접속 자격은 {@code AudioStreamEndpoint} 가 기동 시 만들어
 *     덧붙이므로 여기에 담지 않는다
 */
@ConfigurationProperties(prefix = "audio-clip")
public record AudioClipProperties(
        Duration window, Duration minTranscribable, Integer sampleRate, Integer maxSessions, String streamUrlTemplate) {

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
        // 다운샘플이 정수배 데시메이션이라 16kHz 의 배수만 다룰 수 있다. 여기서 막지 않으면 기동도 되고 버퍼도
        // 쌓이다가 첫 트리거에서야 IllegalArgumentException 이 나고, 그것도 BusinessException 이 아니라 500 이 된다.
        int transcriptionRate = PcmAudioFormat.transcription().sampleRate();
        if (sampleRate % transcriptionRate != 0) {
            throw new IllegalArgumentException(
                    "audio-clip.sample-rate must be a multiple of " + transcriptionRate + ": " + sampleRate);
        }
        if (maxSessions == null || maxSessions <= 0) {
            // 48kHz 5분 = 세션당 약 29MB. 8개면 약 230MB로, 동시 강의 수와 힙 여유 사이의 기본 절충이다.
            maxSessions = 8;
        }
        if (streamUrlTemplate == null || streamUrlTemplate.isBlank()) {
            // Egress 컨테이너가 host 네트워크라 백엔드의 게시 포트(127.0.0.1:18080)에 루프백으로 직결된다.
            streamUrlTemplate = "ws://127.0.0.1:18080/internal/audio/{sessionId}";
        }
    }
}
