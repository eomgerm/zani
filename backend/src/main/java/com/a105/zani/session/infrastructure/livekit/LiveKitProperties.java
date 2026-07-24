package com.a105.zani.session.infrastructure.livekit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** LiveKit 서버 접속·서명 설정. 값은 환경 변수에서만 읽고 응답·로그·DB에 저장하지 않는다. 자격증명이 비어 있어도 애플리케이션 컨텍스트는 기동한다(토큰 발급 시점에만 필요). */
@ConfigurationProperties(prefix = "livekit")
public record LiveKitProperties(String url, String apiKey, String apiSecret, String environment, Duration tokenTtl) {

    public LiveKitProperties {
        if (environment == null || environment.isBlank()) {
            environment = "local";
        }
        if (tokenTtl == null) {
            tokenTtl = Duration.ofMinutes(10);
        }
    }
}
