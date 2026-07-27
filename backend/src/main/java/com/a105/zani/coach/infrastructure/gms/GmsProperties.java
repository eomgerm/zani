package com.a105.zani.coach.infrastructure.gms;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSAFY GMS 접속·서명 설정. API key 는 환경 변수에서만 읽고 응답·로그에 남기지 않는다.
 *
 * <p>202 범위가 쓰는 값만 둔다. tip-model(gpt-5.4-mini) 등은 실제 사용처(204)에서 추가한다. (ddd-development-guide ARCH-008)
 *
 * @param baseUrl GMS 프록시 base URL. 예: https://gms.ssafy.io/gmsapi/api.openai.com
 * @param apiKey GMS API key (Bearer)
 * @param sttModel 실시간 전사에 사용할 STT 모델. 기본 whisper-1 (사용처는 203)
 * @param healthModel 헬스체크 프로브 모델. 품질이 필요 없으므로 가장 저렴한 비추론 모델을 쓴다. 기본 gpt-4.1-nano
 * @param mockEnabled true 면 실제 GMS 호출 없이 mock 응답. 운영 프로파일에서 금지
 * @param healthTimeout 헬스체크 read timeout
 * @param connectTimeout 연결 timeout
 */
@ConfigurationProperties(prefix = "gms")
public record GmsProperties(
        String baseUrl,
        String apiKey,
        String sttModel,
        String healthModel,
        boolean mockEnabled,
        Duration healthTimeout,
        Duration connectTimeout) {}
