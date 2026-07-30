package com.a105.zani.coach.infrastructure.gms;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import com.a105.zani.attention.application.port.CoachingTipType;
import com.a105.zani.coach.application.port.TipConcept;
import com.a105.zani.coach.application.port.TipConceptRequest;
import com.a105.zani.coach.infrastructure.config.CoachTipProperties;
import com.a105.zani.common.infrastructure.gms.GmsProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * 팁 개념 어댑터의 요청 형식과 응답별 처리를 검증한다.
 *
 * <p>실패는 모두 빈 값으로 흡수한다 — 전부 "팁을 만들지 않는다"로 같게 끝나고 재시도가 없어 호출자가 구분해서 할 일이 없다.
 */
class GmsTipConceptHttpAdapterTest {

    private static final String BASE_URL = "https://gms.test";
    private static final String CHAT_URL = BASE_URL + "/v1/chat/completions";

    private GmsProperties gmsProperties() {
        return new GmsProperties(
                BASE_URL,
                "test-key",
                false,
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                "whisper-1",
                Duration.ofSeconds(20),
                "ko",
                "gpt-5.4-mini",
                Duration.ofSeconds(6));
    }

    private TipConceptRequest request() {
        return new TipConceptRequest(CoachingTipType.CONFUSED, "오늘은 제네릭 와일드카드를 설명했습니다.", "자바 기초", 42);
    }

    private record Fixture(GmsTipConceptHttpAdapter adapter, MockRestServiceServer server) {}

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GmsTipConceptHttpAdapter adapter = new GmsTipConceptHttpAdapter(
                builder.build(), JsonMapper.builder().build(), gmsProperties(), new CoachTipProperties(0.5, 60, 3000));
        return new Fixture(adapter, server);
    }

    private static String chatResponse(String content) {
        return """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":%s,"refusal":null}}]}""".formatted(content);
    }

    @Test
    @DisplayName("성공하면 개념과 신뢰도를 돌려주고 요청에 모델·structured outputs 를 싣는다")
    void returnsConceptAndSendsStructuredOutputRequest() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("\"model\":\"gpt-5.4-mini\"")))
                .andExpect(content().string(containsString("\"temperature\":0")))
                // gpt-5.4-mini 는 max_tokens 를 거부한다(실측). 회귀로 못 박는다.
                .andExpect(content().string(containsString("\"max_completion_tokens\":60")))
                .andExpect(content().string(not(containsString("\"max_tokens\""))))
                .andExpect(content().string(containsString("json_schema")))
                .andExpect(content().string(containsString("coaching_tip_concept")))
                .andExpect(content().string(containsString("자바 기초")))
                .andExpect(content().string(containsString("제네릭 와일드카드")))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse("\"{\\\"concept\\\":\\\"제네릭 와일드카드\\\",\\\"confidence\\\":0.82}\""),
                        MediaType.APPLICATION_JSON));

        Optional<TipConcept> concept = fixture.adapter().extract(request());

        assertThat(concept).isPresent();
        assertThat(concept.get().concept()).isEqualTo("제네릭 와일드카드");
        assertThat(concept.get().confidence()).isEqualTo(0.82);
        fixture.server().verify();
    }

    @Test
    @DisplayName("놓침 유형은 핵심 내용을 뽑도록 지시한다")
    void asksForMissedContentOnMissedType() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andExpect(content().string(containsString("놓쳤을 핵심 내용")))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse("\"{\\\"concept\\\":\\\"상한 경계\\\",\\\"confidence\\\":0.7}\""),
                        MediaType.APPLICATION_JSON));

        fixture.adapter().extract(new TipConceptRequest(CoachingTipType.MISSED, "상한 경계를 설명했습니다.", "자바 기초", 10));
        fixture.server().verify();
    }

    @Test
    @DisplayName("전사가 비면 GMS 를 호출하지 않는다")
    void skipsCallWhenTranscriptIsBlank() {
        Fixture fixture = fixture();

        assertThat(fixture.adapter().extract(new TipConceptRequest(CoachingTipType.CONFUSED, "  ", "자바 기초", 10)))
                .isEmpty();
        fixture.server().verify();
    }

    @Test
    @DisplayName("스키마를 벗어난 응답은 폐기한다 (COACH-005)")
    void absorbsSchemaViolation() {
        assertAbsorbs(MockRestResponseCreators.withSuccess(
                chatResponse("\"{\\\"unexpected\\\":true}\""), MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("JSON 이 아닌 본문은 폐기한다")
    void absorbsNonJsonContent() {
        assertAbsorbs(
                MockRestResponseCreators.withSuccess(chatResponse("\"핵심 개념은 제네릭입니다\""), MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("빈 개념은 폐기한다")
    void absorbsBlankConcept() {
        assertAbsorbs(MockRestResponseCreators.withSuccess(
                chatResponse("\"{\\\"concept\\\":\\\"  \\\",\\\"confidence\\\":0.9}\""), MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("모델이 거부하면 폐기한다")
    void absorbsRefusal() {
        assertAbsorbs(MockRestResponseCreators.withSuccess("""
                {"choices":[{"finish_reason":"stop","message":{"content":null,"refusal":"거부"}}]}""", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("출력이 잘리면(finish_reason=length) 폐기한다")
    void absorbsTruncatedOutput() {
        assertAbsorbs(MockRestResponseCreators.withSuccess("""
                {"choices":[{"finish_reason":"length","message":{"content":"{\\"concept\\":\\"제","refusal":null}}]}""", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("choices 가 비면 폐기한다")
    void absorbsEmptyChoices() {
        assertAbsorbs(MockRestResponseCreators.withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("401·402·429·5xx·timeout 을 모두 흡수한다")
    void absorbsFailures() {
        assertAbsorbs(MockRestResponseCreators.withUnauthorizedRequest().body("{\"message\":\"invalid key\"}"));
        assertAbsorbs(MockRestResponseCreators.withStatus(HttpStatus.PAYMENT_REQUIRED)
                .body("{\"message\":\"credit exhausted\"}"));
        assertAbsorbs(MockRestResponseCreators.withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .body("{\"message\":\"slow down\"}"));
        assertAbsorbs(MockRestResponseCreators.withServerError().body("{\"message\":\"unavailable\"}"));
        assertAbsorbs(request -> {
            throw new SocketTimeoutException("read timed out");
        });
    }

    private void assertAbsorbs(ResponseCreator response) {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(CHAT_URL)).andRespond(response);

        assertThat(fixture.adapter().extract(request())).isEmpty();
    }
}
