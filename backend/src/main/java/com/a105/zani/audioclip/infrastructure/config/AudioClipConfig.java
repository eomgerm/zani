package com.a105.zani.audioclip.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.a105.zani.audioclip.domain.model.PcmAudioFormat;
import com.a105.zani.audioclip.infrastructure.buffer.InstructorAudioBuffer;
import com.a105.zani.audioclip.infrastructure.websocket.EgressAudioWebSocketHandler;

/**
 * 강사 오디오 링버퍼와 Egress 수신 WebSocket 을 등록한다.
 *
 * <p>수신 경로는 사용자 브라우저가 아니라 Egress 노드 전용이라 CORS(allowedOrigins)를 열지 않고, 공유 시크릿으로만 검증한다.
 */
@Configuration
@EnableWebSocket
@EnableConfigurationProperties(AudioClipProperties.class)
public class AudioClipConfig implements WebSocketConfigurer {

    /** Egress 수신 경로. SecurityConfig 의 permitAll 목록과 함께 바뀌어야 한다. */
    public static final String AUDIO_STREAM_PATH = "/internal/audio/{sessionId}";

    private final AudioClipProperties properties;

    public AudioClipConfig(AudioClipProperties properties) {
        this.properties = properties;
    }

    @Bean
    public InstructorAudioBuffer instructorAudioBuffer() {
        PcmAudioFormat format = new PcmAudioFormat(properties.sampleRate(), 1, 16);
        return new InstructorAudioBuffer(format, properties.window());
    }

    @Bean
    public EgressAudioWebSocketHandler egressAudioWebSocketHandler() {
        return new EgressAudioWebSocketHandler(instructorAudioBuffer(), properties.streamSecret());
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(egressAudioWebSocketHandler(), AUDIO_STREAM_PATH);
    }
}
