package com.a105.zani.coach.infrastructure.gms;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * GMS 호출용 RestClient 를 한 곳에서 구성한다(baseUrl·Bearer 인증·timeout).
 *
 * <p>RestClient 를 쓰는 이유: 전사(203)는 multipart 오디오 업로드라 선언형 클라이언트보다 요청 구성이 단순하고, GMS 오류를 예외 전파 없이 흡수하는 패턴이라 명령형 호출이 다루기
 * 쉽다.
 *
 * <p>timeout 요구가 호출마다 달라 client 를 분리한다. RestClient 는 timeout 을 requestFactory 에 고정하므로 한 빈으로는 나눌 수 없다. 인증·baseUrl 구성은
 * 공유하고 timeout 만 다르게 둔다.
 *
 * <ul>
 *   <li>{@code gmsRestClient} — 기본값({@code read-timeout})
 *   <li>{@code gmsTranscriptionRestClient} — 전사 전용({@code transcribe-timeout}, 기본 10초)
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(GmsProperties.class)
public class GmsClientConfig {

    @Bean
    public RestClient gmsRestClient(GmsProperties properties) {
        return buildClient(properties, properties.readTimeout());
    }

    @Bean
    public RestClient gmsTranscriptionRestClient(GmsProperties properties) {
        return buildClient(properties, properties.transcribeTimeout());
    }

    private RestClient buildClient(GmsProperties properties, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .requestFactory(factory)
                .build();
    }
}
