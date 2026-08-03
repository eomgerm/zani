package com.a105.zani.session.infrastructure.livekit;

import io.livekit.server.RoomServiceClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
