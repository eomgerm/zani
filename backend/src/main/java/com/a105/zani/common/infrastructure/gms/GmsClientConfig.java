package com.a105.zani.common.infrastructure.gms;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * GMS 호출용 RestClient 를 한 곳에서 구성한다(baseUrl·Bearer 인증·timeout).
 *
 * <p>{@code common} 에 두는 이유: GMS 는 한 도메인의 클라이언트가 아니라 여러 도메인이 공유하는 외부 시스템이다. 전사(203)는 {@code audioclip}, 팁 생성(204)은
 * {@code coach} 가 각자 자기 어댑터를 갖고 이 접속 설정만 공유한다. 접속 설정을 어느 한 업무 도메인에 두면 다른 도메인이 타 도메인 infrastructure 를 import 해야 해 의존 규칙을
 * 어긴다. (ddd-development-guide §13 — 여러 도메인이 공유하는 프레임워크 설정)
 *
 * <p>RestClient 를 쓰는 이유: 전사는 multipart 오디오 업로드라 선언형 클라이언트보다 요청 구성이 단순하고, 벤더 오류를 어댑터가 직접 다루는 패턴이라 명령형 호출이 편하다.
 *
 * <p>timeout 요구가 호출마다 달라 client 를 분리한다. RestClient 는 timeout 을 requestFactory 에 고정하므로 한 빈으로는 나눌 수 없다. 인증·baseUrl 구성은
 * 공유하고, 전사 클라이언트만 GMS multipart 호환을 위해 요청 버퍼링도 적용한다.
 *
 * <ul>
 *   <li>{@code gmsRestClient} — 기본값({@code read-timeout})
 *   <li>{@code gmsTranscriptionRestClient} — 전사 전용({@code transcribe-timeout}, Content-Length 확정)
 *   <li>{@code gmsTipRestClient} — 팁 문구 전용({@code tip-timeout})
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(GmsProperties.class)
public class GmsClientConfig {

    @Bean
    public RestClient gmsRestClient(GmsProperties properties) {
        return buildClient(properties, properties.readTimeout(), false);
    }

    @Bean
    public RestClient gmsTranscriptionRestClient(GmsProperties properties) {
        // GMS의 OpenAI 전달 구간은 chunked multipart를 400으로 거부한다. 전사 입력은 이미 byte[]이고
        // 최대 5분 MP3도 약 2.4MB이므로 이 클라이언트만 한 번 더 버퍼링해 Content-Length를 확정한다.
        return buildClient(properties, properties.transcribeTimeout(), true);
    }

    @Bean
    public RestClient gmsTipRestClient(GmsProperties properties) {
        return buildClient(properties, properties.tipTimeout(), false);
    }

    private RestClient buildClient(GmsProperties properties, Duration readTimeout, boolean bufferRequest) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        ClientHttpRequestFactory requestFactory =
                bufferRequest ? new BufferingClientHttpRequestFactory(factory) : factory;
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .requestFactory(requestFactory)
                .build();
    }
}
