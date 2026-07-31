package com.a105.zani.session.infrastructure.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import com.a105.zani.common.config.CorsProperties;

/**
 * 업무 이벤트용 STOMP 브로커. 채팅·손들기·반응이 이 경로로 오간다.
 *
 * <p><b>LiveKit 과 역할이 다르다.</b> 미디어(오디오·비디오·화면)는 LiveKit 이 나르고, DataPacket 은 쓰지 않는다. 업무 이벤트는 전부 이 브로커를 지난다.
 *
 * <p><b>내장 브로커의 한계.</b> {@code enableSimpleBroker} 는 구독 정보를 이 프로세스 메모리에 둔다. 인스턴스를 여러 개로 늘리면 각 인스턴스에 붙은 클라이언트끼리 메시지가 오가지
 * 않는다. 현재 배포는 단일 인스턴스라 문제가 없고, 늘릴 때는 외부 브로커(또는 Redis relay)로 바꾼다.
 *
 * <p>SockJS 폴백은 붙이지 않는다. 대상 브라우저가 모두 WebSocket 을 지원하고, 폴백을 켜면 경로가 하나 더 생겨 인증·CORS 를 두 곳에서 맞춰야 한다.
 */
@Configuration
@EnableWebSocketMessageBroker
public class StompConfig implements WebSocketMessageBrokerConfigurer {

    private final CorsProperties corsProperties;
    private final StompAuthChannelInterceptor authChannelInterceptor;

    public StompConfig(CorsProperties corsProperties, StompAuthChannelInterceptor authChannelInterceptor) {
        this.corsProperties = corsProperties;
        this.authChannelInterceptor = authChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 핸드셰이크에는 토큰이 없으므로(브라우저가 헤더를 못 붙인다) 출처 제한이 첫 관문이다. 인증은 CONNECT 프레임에서 한다.
        registry.addEndpoint(SessionChannelDestinations.HANDSHAKE_PATH)
                .setAllowedOriginPatterns(corsProperties.allowedOrigins().toArray(new String[0]));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker(SessionChannelDestinations.BROKER_PREFIX);
        registry.setApplicationDestinationPrefixes(SessionChannelDestinations.APPLICATION_PREFIX);
        registry.setUserDestinationPrefix(SessionChannelDestinations.USER_PREFIX);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }
}
