package com.a105.zani.session.infrastructure.livekit;

import java.util.concurrent.Executor;

import io.livekit.server.RoomServiceClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties(LiveKitProperties.class)
public class LiveKitConfig {

    /**
     * 자격증명이 없을 때 쓰는 주소.
     *
     * <p>{@code RoomServiceClient.create} 는 내부적으로 Retrofit 을 세우면서 <b>스킴 있는 URL 을 요구한다.</b> 빈 문자열을 주면 빈 생성이 실패하고 컨텍스트가
     * 통째로 뜨지 않는다 — LiveKit 을 쓰지 않는 테스트까지 전부 죽는다.
     *
     * <p>이 주소로 실제 요청이 나가면 연결이 거부되고, 어댑터가 그것을 {@code UNAVAILABLE} 로 바꾼다. 잘못 설정된 채 조용히 성공하는 것보다 낫다.
     */
    private static final String UNCONFIGURED_URL = "http://localhost:1";

    /**
     * LiveKit 서버 제어 클라이언트. 토큰 발급(서명)과 달리 <b>서버에 실제로 요청을 보내는</b> 경로다.
     *
     * <p>자격증명이 비어 있어도 빈을 만든다. {@code LiveKitProperties} 가 같은 이유로 빈 값을 허용하는데, 여기서 막으면 LiveKit 없이 도는 로컬·테스트 환경의 컨텍스트가 아예
     * 뜨지 않는다. 실제로 못 쓰는 상황은 호출 시점에 드러나고, 어댑터가 그것을 {@code UNAVAILABLE} 로 바꾼다.
     */
    @Bean
    public RoomServiceClient roomServiceClient(LiveKitProperties properties) {
        String url = properties.url() == null || properties.url().isBlank() ? UNCONFIGURED_URL : properties.url();
        return RoomServiceClient.create(
                url,
                properties.apiKey() == null ? "" : properties.apiKey(),
                properties.apiSecret() == null ? "" : properties.apiSecret());
    }

    /**
     * 세션 종료의 미디어 뒷정리(Egress 중지·room 삭제) 전용 executor.
     *
     * <p><b>왜 호출 스레드에서 떼어내는가.</b> 뒷정리는 room 의 살아 있는 Egress 를 하나씩 멈추고 room 을 닫는데, LiveKit 호출 한 건이 최대 60초 매달릴 수 있다(
     * {@code LiveKitTrackEgressAdapter.CALL_TIMEOUT}). 참가자마다 트랙이 여러 개라 한 세션에 열 건이 넘고, 종료를 부르는 세 경로가 모두 그 시간을 그대로 뒤집어쓴다
     * — heartbeat·강사 종료 요청은 HTTP 스레드, 만료 스윕은 {@code @Scheduled} 스레드다. 특히 스윕은 한 번에 최대 50건을 처리하므로,
     * {@code spring.task.scheduling.pool.size} 를 스케줄 작업 수에 맞춰 산정해 둔 계산(100ms 주기 무음 패딩이 굶지 않게)이 통째로 무너진다.
     *
     * <p>{@code @Async} 로 감추지 않고 executor 를 직접 주입한다(coach 의 팁 executor 와 같은 방식).
     *
     * <p>거절 정책만 팁 executor 와 다르다. 큐가 차면 <b>호출 스레드에서 실행</b>한다 — 뒷정리를 버리면 아직 도는 Egress 가 room TTL 로 입력을 잃은 채 끝나 녹화 파일이
     * 온전히 닫히지 않는다. 종료가 느려지는 것보다 녹화를 잃는 쪽이 나쁘다. 큐 200 은 스윕 한 배치(50)의 네 배라, 이 경로는 LiveKit 이 오래 막힌 예외 상황에서만 열린다.
     *
     * <p>{@code Executor} 타입 빈이 둘(팁 생성·이 뒷정리)이라 타입만으로는 고를 수 없다. 쓰는 쪽({@code EndSessionService})이 이 빈 이름을
     * {@code @Qualifier} 로 못박으므로, 이름을 바꾸면 그쪽도 함께 고쳐야 한다.
     */
    @Bean
    public Executor mediaCleanupExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("session-end-cleanup-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        // 배포로 내려갈 때 진행 중인 정리를 기다린다. 끊으면 그 세션의 Egress·room 이 열린 채 남는다.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
