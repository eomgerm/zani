package com.a105.zani.coach.infrastructure.gms;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * GMS 호출용 RestClient 를 한 곳에서 구성한다(baseUrl·Bearer 인증·timeout). 203(전사)·204(팁) 어댑터가 같은 설정 패턴을 재사용한다.
 *
 * <p>202 는 헬스체크만 하므로 read timeout 을 health-timeout 으로 둔다. 더 긴 timeout 이 필요한 전사(203)·팁(204)은 각자 필요 시 별도 client 를 도입한다.
 */
@Configuration
@EnableConfigurationProperties(GmsProperties.class)
public class GmsClientConfig {

    @Bean
    public RestClient gmsRestClient(GmsProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        factory.setReadTimeout((int) properties.healthTimeout().toMillis());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .requestFactory(factory)
                .build();
    }
}
