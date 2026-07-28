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

    private final org.springframework.beans.factory.ObjectProvider<EgressAudioWebSocketHandler> handlerProvider;

    public AudioClipConfig(
            AudioClipProperties properties,
            org.springframework.beans.factory.ObjectProvider<EgressAudioWebSocketHandler> handlerProvider) {
        this.properties = properties;
        this.handlerProvider = handlerProvider;
    }

    @Bean
    public InstructorAudioBuffer instructorAudioBuffer(java.time.Clock clock) {
        PcmAudioFormat format = new PcmAudioFormat(properties.sampleRate(), 1, 16);
        return new InstructorAudioBuffer(format, properties.window(), clock);
    }

    @Bean
    public EgressAudioWebSocketHandler egressAudioWebSocketHandler(InstructorAudioBuffer buffer) {
        return new EgressAudioWebSocketHandler(buffer, properties.streamSecret());
    }

    /**
     * 등록 시점에는 핸들러 빈이 이미 만들어져 있어야 하므로 컨텍스트에서 받아 쓴다. 설정 클래스 안에서 {@code egressAudioWebSocketHandler(...)} 를 직접 호출하면 프록시를
     * 거치지 않는 새 인스턴스가 생겨 버퍼가 갈릴 수 있다.
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handlerProvider.getObject(), AUDIO_STREAM_PATH);
    }
}
