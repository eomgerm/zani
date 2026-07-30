package com.a105.zani.coach.infrastructure.gms;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import com.a105.zani.attention.application.port.CoachingTipType;
import com.a105.zani.coach.application.port.TipConcept;
import com.a105.zani.coach.application.port.TipConceptPort;
import com.a105.zani.coach.application.port.TipConceptRequest;
import com.a105.zani.coach.infrastructure.config.CoachTipProperties;
import com.a105.zani.common.infrastructure.gms.GmsProperties;

/**
 * GMS gpt-5.4-mini 로 팁 문구의 자리표시자 값을 뽑는다. (S15P11A105-204)
 *
 * <p>{@code POST /v1/chat/completions} 에 structured outputs 로 요청한다. 실측으로 확인한 이 모델의 제약이 두 가지다.
 *
 * <ul>
 *   <li>{@code max_tokens} 는 400 으로 거부된다 — {@code max_completion_tokens} 를 써야 한다
 *   <li>{@code response_format} 의 {@code json_schema}(strict)는 지원된다. 스키마를 강제하므로 프롬프트로 형식을 부탁하는 방식보다 검증 실패가 적다
 * </ul>
 *
 * <p>재시도는 하지 않는다. 실패·timeout·자격증명 오류·크레딧 소진·rate limit·서버 오류·스키마 위반을 모두 빈 값으로 흡수한다 — 전부 "팁을 만들지 않는다"로 같게 끝나고, 팁이 없어도
 * 수업은 계속된다(COACH-003).
 *
 * <p>벤더 요청·응답 타입은 이 어댑터 안에서만 다룬다.
 */
@Component
@EnableConfigurationProperties(CoachTipProperties.class)
@ConditionalOnProperty(prefix = "gms", name = "mock-enabled", havingValue = "false")
public class GmsTipConceptHttpAdapter implements TipConceptPort {

    private static final Logger log = LoggerFactory.getLogger(GmsTipConceptHttpAdapter.class);
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    /**
     * 출력 스키마. {@code strict} 와 {@code additionalProperties: false} 가 함께 있어야 모델이 스키마를 벗어나지 못한다.
     *
     * <p>조사(을/를)를 붙이지 말라고 지시하는 이유: §8 문구가 "{핵심 개념}을" 형태라 조사를 우리가 붙인다. 모델이 함께 넣으면 "와일드카드를을" 이 된다.
     */
    private static final Map<String, Object> RESPONSE_FORMAT = Map.of(
            "type",
            "json_schema",
            "json_schema",
            Map.of(
                    "name",
                    "coaching_tip_concept",
                    "strict",
                    true,
                    "schema",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "concept", Map.of("type", "string"),
                                    "confidence", Map.of("type", "number")),
                            "required",
                            List.of("concept", "confidence"),
                            "additionalProperties",
                            false)));

    private final RestClient tipRestClient;
    private final ObjectMapper objectMapper;
    private final String tipModel;
    private final int maxCompletionTokens;

    public GmsTipConceptHttpAdapter(
            @Qualifier("gmsTipRestClient") RestClient tipRestClient,
            ObjectMapper objectMapper,
            GmsProperties gmsProperties,
            CoachTipProperties tipProperties) {
        this.tipRestClient = tipRestClient;
        this.objectMapper = objectMapper;
        this.tipModel = gmsProperties.tipModel();
        this.maxCompletionTokens = tipProperties.maxCompletionTokens();
    }

    @Override
    public Optional<TipConcept> extract(TipConceptRequest request) {
        if (request == null
                || request.transcriptTail() == null
                || request.transcriptTail().isBlank()) {
            log.warn("Tip concept skipped: empty transcript");
            return Optional.empty();
        }

        long startedAt = System.nanoTime();
        try {
            ChatResponse response = tipRestClient
                    .post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(request))
                    .retrieve()
                    .body(ChatResponse.class);

            return parse(response, elapsedMs(startedAt));
        } catch (RuntimeException exception) {
            // timeout, 401(자격증명), 402(크레딧 소진), 429(rate limit), 5xx 를 모두 같은 실패로 다룬다.
            log.warn("Tip concept failed after {}ms: {}", elapsedMs(startedAt), exception.toString());
            return Optional.empty();
        }
    }

    private Map<String, Object> requestBody(TipConceptRequest request) {
        return Map.of(
                "model",
                tipModel,
                "temperature",
                0,
                "max_completion_tokens",
                maxCompletionTokens,
                "response_format",
                RESPONSE_FORMAT,
                "messages",
                List.of(
                        Map.of("role", "system", "content", systemPrompt(request.tipType())),
                        Map.of("role", "user", "content", userPrompt(request))));
    }

    private String systemPrompt(CoachingTipType tipType) {
        String slot = tipType == CoachingTipType.CONFUSED ? "학생들이 헷갈릴 만한 핵심 개념" : "학생들이 놓쳤을 핵심 내용";
        return """
                너는 강사의 최근 발화를 읽고 %s 하나를 뽑는다.
                규칙:
                - 발화에 실제로 나온 표현만 쓴다. 없는 내용을 만들지 않는다.
                - 조사(을/를/은/는)를 붙이지 않는다. 명사구로만 답한다.
                - 25자 이내로 짧게 쓴다.
                - 뽑을 만한 개념이 없으면 confidence 를 0.2 이하로 둔다.""".formatted(slot);
    }

    private String userPrompt(TipConceptRequest request) {
        return """
                수업 제목: %s
                수업 경과: %d분
                강사 최근 발화:
                %s""".formatted(request.lectureTitle(), request.elapsedMinutes(), request.transcriptTail());
    }

    private Optional<TipConcept> parse(ChatResponse response, long elapsedMs) {
        Choice choice = response == null
                        || response.choices() == null
                        || response.choices().isEmpty()
                ? null
                : response.choices().getFirst();
        if (choice == null || choice.message() == null) {
            log.warn("Tip concept failed after {}ms: empty choices", elapsedMs);
            return Optional.empty();
        }
        if (choice.message().refusal() != null) {
            log.warn("Tip concept refused after {}ms", elapsedMs);
            return Optional.empty();
        }
        if (!"stop".equals(choice.finishReason())) {
            // length 면 JSON 이 잘려 파싱도 실패한다. 사유를 남겨 max_completion_tokens 를 의심할 수 있게 한다.
            log.warn("Tip concept incomplete after {}ms: finishReason={}", elapsedMs, choice.finishReason());
            return Optional.empty();
        }
        try {
            TipConcept concept = objectMapper.readValue(choice.message().content(), TipConcept.class);
            if (concept.isBlank()) {
                log.warn("Tip concept blank after {}ms", elapsedMs);
                return Optional.empty();
            }
            log.info("Tip concept extracted: elapsedMs={} confidence={}", elapsedMs, concept.confidence());
            return Optional.of(concept);
        } catch (Exception exception) {
            // strict 스키마를 썼어도 모델이 스키마 밖 응답을 낼 여지를 남긴다 — 검증 실패는 팁을 만들지 않는 사유다(COACH-005).
            log.warn("Tip concept schema invalid after {}ms: {}", elapsedMs, exception.toString());
            return Optional.empty();
        }
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    /** GMS(OpenAI 호환) chat completions 응답. 필요한 필드만 받는다. */
    private record ChatResponse(List<Choice> choices) {}

    private record Choice(
            Message message, @JsonProperty("finish_reason") String finishReason) {}

    private record Message(String content, String refusal) {}
}
