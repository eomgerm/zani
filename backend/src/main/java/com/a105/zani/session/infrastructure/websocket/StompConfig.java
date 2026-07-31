package com.a105.zani.session.infrastructure.websocket;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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
public class StompConfig implements WebSocketMessageBrokerConfigurer, DisposableBean {

    /**
     * 하트비트 간격(ms). {서버가 보내는 주기, 클라이언트에 기대하는 주기}.
     *
     * <p>흔한 프록시 유휴 한계(30~60초)보다 짧게 잡는다. 그래야 우리가 설정을 손댈 수 없는 중간 홉 — 사내 프록시, 모바일 NAT, 가정용 공유기 — 도 연결을 살려 둔다.
     */
    private static final long[] HEARTBEAT_MS = {25_000, 25_000};

    private final CorsProperties corsProperties;
    private final StompAuthChannelInterceptor authChannelInterceptor;
    private final ThreadPoolTaskScheduler heartbeatScheduler = createHeartbeatScheduler();

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
        // 스케줄러를 주지 않으면 Spring 은 CONNECTED 에 heart-beat:0,0 을 실어 보낸다. STOMP 규약상 한쪽이
        // 0 이면 그 방향이 꺼지므로, 클라이언트가 하트비트를 요청해도 양방향 모두 프레임이 흐르지 않는다.
        //
        // 그 상태에서는 조용한 수업의 연결이 몇 시간 동안 바이트를 하나도 보내지 않아 중간 홉이 끊는다.
        // 자동 재연결이 있어 복구는 되지만, 재연결마다 스냅샷을 다시 받고 그동안 버튼이 잠긴다.
        // 죽은 상대를 감지할 수단이 없어 half-open 연결이 TCP keepalive 까지 남는 문제도 함께 없앤다.
        registry.enableSimpleBroker(SessionChannelDestinations.BROKER_PREFIX)
                .setTaskScheduler(heartbeatScheduler)
                .setHeartbeatValue(HEARTBEAT_MS);
        registry.setApplicationDestinationPrefixes(SessionChannelDestinations.APPLICATION_PREFIX);
        registry.setUserDestinationPrefix(SessionChannelDestinations.USER_PREFIX);
    }

    /**
     * 브로커 하트비트 전용 스케줄러.
     *
     * <p><b>빈으로 등록하지 않는다.</b> {@code TaskScheduler} 타입 빈이 하나라도 있으면 부트의 {@code TaskSchedulingAutoConfiguration} 이
     * {@code @ConditionalOnMissingBean} 으로 물러나고, {@code @Scheduled} 작업이 전부 이 스케줄러로 옮겨 온다. 그러면
     * {@code spring.task.scheduling.pool.size=4} 설정이 조용히 무시되면서 100ms 주기인 강사 오디오 무음 패딩이 굶는다 — 스케줄러를 따로 두려던 의도와 정반대 결과다.
     *
     * <p>풀을 나눠 쓰지 않는 이유는 반대 방향으로도 성립한다. 녹화 outbox 릴레이는 건마다 LiveKit 호출이 최대 60초 매달릴 수 있어, 같은 풀이면 하트비트가 그동안 밀려 고치려던 끊김이
     * 그대로 일어난다.
     */
    private static ThreadPoolTaskScheduler createHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stomp-heartbeat-");
        // 종료 시 남은 하트비트를 기다리지 않는다. 어차피 연결이 닫히는 중이다.
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.afterPropertiesSet();
        return scheduler;
    }

    @Override
    public void destroy() {
        heartbeatScheduler.shutdown();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }
}
