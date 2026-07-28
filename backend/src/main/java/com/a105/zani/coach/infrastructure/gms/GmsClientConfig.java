package com.a105.zani.coach.infrastructure.gms;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * GMS 호출용 RestClient 를 한 곳에서 구성한다(baseUrl·Bearer 인증·timeout). 전사(203)·팁(204) 어댑터가 이 빈을 재사용해 인증과 오류 처리를 한 벌로 공유한다.
 *
 * <p>RestClient 를 쓰는 이유: 전사(203)는 multipart 오디오 업로드라 선언형 클라이언트보다 요청 구성이 단순하고, 호출별 timeout 요구가 다르다(전사 10초·팁 6초). 또한 GMS
 * 오류를 예외 전파 없이 흡수하는 패턴이라 명령형 호출이 다루기 쉽다.
 *
 * <p>{@code read-timeout} 은 기본값이며, 더 긴·짧은 timeout 이 필요한 어댑터는 이 설정을 바탕으로 자기 client 를 구성한다.
 */
@Configuration
@EnableConfigurationProperties(GmsProperties.class)
public class GmsClientConfig {

    @Bean
    public RestClient gmsRestClient(GmsProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        factory.setReadTimeout((int) properties.readTimeout().toMillis());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .requestFactory(factory)
                .build();
    }
}
