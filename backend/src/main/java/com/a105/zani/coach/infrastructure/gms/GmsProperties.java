package com.a105.zani.coach.infrastructure.gms;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSAFY GMS 접속 설정. API key 는 환경 변수에서만 읽고 응답·로그에 남기지 않는다.
 *
 * <p>202 범위가 쓰는 값만 둔다. 모델 이름은 실제 호출이 생기는 티켓에서 추가한다 — 전사 모델(whisper-1)은 203, 팁 모델(gpt-5.4-mini)은 204.
 * (ddd-development-guide ARCH-008)
 *
 * @param baseUrl GMS 프록시 base URL. 예: https://gms.ssafy.io/gmsapi/api.openai.com
 * @param apiKey GMS API key (Bearer)
 * @param mockEnabled true 면 실제 GMS 를 호출하지 않는다. 운영 프로파일에서 금지
 * @param readTimeout 응답 대기 기본값. 호출별 요구(전사 10초·팁 6초)가 다르면 해당 어댑터가 자기 client 를 구성한다
 * @param connectTimeout 연결 timeout
 */
@ConfigurationProperties(prefix = "gms")
public record GmsProperties(
        String baseUrl, String apiKey, boolean mockEnabled, Duration readTimeout, Duration connectTimeout) {}
