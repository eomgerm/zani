package com.a105.zani.coach.infrastructure.gms;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.a105.zani.coach.application.port.GmsHealthPort;

/**
 * 실제 GMS 호출로 코칭 사용 가능 여부를 확인한다.
 *
 * <p>GMS 는 요청에 model 이 있어야 라우팅하는 추론 프록시라 {@code GET /v1/models} 같은 메타데이터 조회를 지원하지 않는다(400 "Model not found in
 * request"). 그래서 가장 저렴한 비추론 모델로 최소 chat 프로브를 1회 보내 자격증명·GMS 장애·크레딧 소진을 감지한다. 실측: gpt-4.1-nano + max_tokens=1 → 200, 약
 * 0.7초.
 *
 * <p>한계: 프로브 모델 경로가 살아있음만 확인한다. whisper-1(203)·팁 모델(204) 개별 장애는 감지하지 못하며, 그 실패는 각 UseCase 가 흡수한다.
 *
 * <p>벤더 응답·오류는 이 어댑터 안에서만 다루고, 실패·timeout·비인증은 예외 없이 false 로 흡수한다.
 */
@Component
@ConditionalOnProperty(prefix = "gms", name = "mock-enabled", havingValue = "false")
public class GmsHealthHttpAdapter implements GmsHealthPort {

    private static final Logger log = LoggerFactory.getLogger(GmsHealthHttpAdapter.class);
    private static final String PROBE_PROMPT = "ping";

    private final RestClient gmsRestClient;
    private final String healthModel;

    public GmsHealthHttpAdapter(RestClient gmsRestClient, GmsProperties properties) {
        this.gmsRestClient = gmsRestClient;
        this.healthModel = properties.healthModel();
    }

    @Override
    public boolean isGmsReachable() {
        try {
            gmsRestClient
                    .post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model",
                            healthModel,
                            "max_tokens",
                            1,
                            "messages",
                            List.of(Map.of("role", "user", "content", PROBE_PROMPT))))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException exception) {
            // 401(자격증명), 402(크레딧 소진), 5xx, timeout 모두 코칭 비활성으로 흡수한다. 키 값은 로그에 남기지 않는다.
            log.warn("GMS health check failed with model {}: {}", healthModel, exception.toString());
            return false;
        }
    }
}
