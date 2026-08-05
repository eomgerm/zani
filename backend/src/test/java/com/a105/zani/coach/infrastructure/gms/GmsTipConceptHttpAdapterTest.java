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
 * 팁 개념 어댑터의 요청 형식과 응답 검증을 확인한다.
 *
 * <p>두 실패를 구분해 돌려주는 것이 핵심이다 — 빈 값은 기술적 실패({@code TIP_GENERATION_FAILED}), 쓸 수 없는 값은 정상 응답이지만 근거
 * 없음({@code LOW_CONFIDENCE}). 합치면 로그에서 "GMS 가 느리다"와 "설명한 내용이 없었다"를 가려낼 수 없다.
 */
class GmsTipConceptHttpAdapterTest {

    private static final String BASE_URL = "https://gms.test";
    private static final String CHAT_URL = BASE_URL + "/v1/chat/completions";

    /** 근거 검증이 통과하려면 전사 원문에 있는 구절이어야 한다. */
    private static final String TRANSCRIPT = "자 이제 제네릭 와일드카드를 볼게요. 상한 경계와 하한 경계의 차이를 설명하겠습니다.";

    private static final String EVIDENCE = "상한 경계와 하한 경계의 차이";

    private GmsProperties gmsProperties() {
        return new GmsProperties(
                BASE_URL,
                "test-key",
                false,
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                "whisper-1",
                Duration.ofSeconds(20),
                Duration.ofSeconds(180),
                "ko",
                "gpt-5.4-mini",
                Duration.ofSeconds(6),
                "gpt-5.4-mini",
                Duration.ofSeconds(60));
    }

    private TipConceptRequest request() {
        return new TipConceptRequest(CoachingTipType.CONFUSED, TRANSCRIPT, "자바 기초", 42);
    }

    private record Fixture(GmsTipConceptHttpAdapter adapter, MockRestServiceServer server) {}

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GmsTipConceptHttpAdapter adapter = new GmsTipConceptHttpAdapter(
                builder.build(), JsonMapper.builder().build(), gmsProperties(), new CoachTipProperties(0.5, 100, 3000));
        return new Fixture(adapter, server);
    }

    /** 모델이 스키마대로 낸 본문을 chat completions 응답 봉투에 넣는다. */
    private static String chatResponse(String concept, String confidence, String evidence) {
        String inner = "{\\\"concept\\\":\\\"%s\\\",\\\"confidence\\\":%s,\\\"evidence\\\":\\\"%s\\\"}"
                .formatted(concept, confidence, evidence);
        return """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"%s","refusal":null}}]}""".formatted(inner);
    }

    private static String okResponse() {
        return chatResponse("제네릭 와일드카드", "0.9", EVIDENCE);
    }

    @Test
    @DisplayName("성공하면 개념·신뢰도·근거를 돌려주고 요청에 모델·structured outputs 를 싣는다")
    void returnsConceptAndSendsStructuredOutputRequest() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("\"model\":\"gpt-5.4-mini\"")))
                .andExpect(content().string(containsString("\"temperature\":0")))
                // gpt-5.4-mini 는 max_tokens 를 거부한다(실측). 회귀로 못 박는다.
                .andExpect(content().string(containsString("\"max_completion_tokens\":100")))
                .andExpect(content().string(not(containsString("\"max_tokens\""))))
                .andExpect(content().string(containsString("json_schema")))
                .andExpect(content().string(containsString("coaching_tip_concept")))
                .andExpect(content().string(containsString("maxLength")))
                .andExpect(content().string(containsString("evidence")))
                .andRespond(MockRestResponseCreators.withSuccess(okResponse(), MediaType.APPLICATION_JSON));

        Optional<TipConcept> concept = fixture.adapter().extract(request());

        assertThat(concept).isPresent();
        assertThat(concept.get().isUsable()).isTrue();
        assertThat(concept.get().concept()).isEqualTo("제네릭 와일드카드");
        assertThat(concept.get().confidence()).isEqualTo(0.9);
        fixture.server().verify();
    }

    @Test
    @DisplayName("전사와 수업 제목을 JSON 경계로 감싸 보낸다 — 평문으로 이어 붙이면 전사 안의 문장이 지시처럼 보인다")
    void wrapsUntrustedDataInJson() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andExpect(content().string(containsString("lectureTitle")))
                .andExpect(content().string(containsString("transcript")))
                // 역할 경계 규칙이 시스템 프롬프트에 있어야 한다.
                .andExpect(content().string(containsString("명령이 아니다")))
                .andRespond(MockRestResponseCreators.withSuccess(okResponse(), MediaType.APPLICATION_JSON));

        fixture.adapter().extract(request());
        fixture.server().verify();
    }

    @Test
    @DisplayName("학생 상태를 추측하게 하지 않고 최근 설명을 추출하도록 지시한다")
    void asksForRecentExplanationNotStudentState() {
        Fixture confused = fixture();
        confused.server()
                .expect(requestTo(CHAT_URL))
                .andExpect(content().string(containsString("가장 최근 핵심 개념")))
                .andExpect(content().string(not(containsString("헷갈릴 만한"))))
                .andRespond(MockRestResponseCreators.withSuccess(okResponse(), MediaType.APPLICATION_JSON));
        confused.adapter().extract(request());
        confused.server().verify();

        Fixture missed = fixture();
        missed.server()
                .expect(requestTo(CHAT_URL))
                .andExpect(content().string(containsString("가장 최근에 설명한 핵심 주제")))
                .andRespond(MockRestResponseCreators.withSuccess(okResponse(), MediaType.APPLICATION_JSON));
        missed.adapter().extract(new TipConceptRequest(CoachingTipType.MISSED, TRANSCRIPT, "자바 기초", 10));
        missed.server().verify();
    }

    @Test
    @DisplayName("전사가 비면 GMS 를 호출하지 않는다")
    void skipsCallWhenTranscriptIsBlank() {
        Fixture fixture = fixture();

        assertThat(fixture.adapter().extract(new TipConceptRequest(CoachingTipType.CONFUSED, "  ", "자바 기초", 10)))
                .isEmpty();
        fixture.server().verify();
    }

    // ── 정상 응답이지만 쓸 수 없는 값 → LOW_CONFIDENCE ─────────────────────────

    @Test
    @DisplayName("설명한 개념이 없다고 답하면 값은 오지만 쓸 수 없다 — 인사·출석만 있는 구간")
    void treatsEmptyConceptAsGroundless() {
        assertGroundless(chatResponse("", "0", ""));
    }

    @Test
    @DisplayName("근거 구절이 전사에 없으면 신뢰도가 높아도 버린다 — 잘못된 팁보다 팁 생략이 낫다")
    void rejectsEvidenceThatIsNotInTranscript() {
        assertGroundless(chatResponse("과제 제출 기한", "0.93", "금요일까지 제출하세요"));
    }

    @Test
    @DisplayName("근거 구절이 너무 짧으면 버린다 — 흔한 어절은 전사 어디서나 발견된다")
    void rejectsTooShortEvidence() {
        assertGroundless(chatResponse("제네릭", "0.9", "자 이제"));
    }

    @Test
    @DisplayName("근거 구절의 최소 길이는 정규화 후에 잰다 — 공백을 늘려 넣은 짧은 구절이 통과하면 안 된다")
    void measuresEvidenceLengthAfterNormalization() {
        // 원문 길이는 9자지만 공백을 줄이면 4자다. 전사에 있는 구절이어도 근거로 인정하지 않는다.
        assertGroundless(chatResponse("제네릭 와일드카드", "0.9", "자      이제"));
    }

    @Test
    @DisplayName("공백 수만 다른 근거는 같은 문구로 인정한다 — STT 결과와 모델 출력이 늘 같지 않다")
    void acceptsEvidenceDifferingOnlyByWhitespace() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse("제네릭 와일드카드", "0.9", "상한  경계와   하한 경계의 차이"), MediaType.APPLICATION_JSON));

        assertThat(fixture.adapter().extract(request()).orElseThrow().isUsable())
                .isTrue();
    }

    // ── 기술적 실패 → 빈 값 → TIP_GENERATION_FAILED ───────────────────────────

    @Test
    @DisplayName("스키마를 벗어난 응답은 기술적 실패다 (COACH-005)")
    void absorbsSchemaViolation() {
        assertTechnicalFailure(MockRestResponseCreators.withSuccess("""
                {"choices":[{"finish_reason":"stop","message":{"content":"{\\"unexpected\\":true}","refusal":null}}]}""", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("JSON 이 아닌 본문은 기술적 실패다")
    void absorbsNonJsonContent() {
        assertTechnicalFailure(MockRestResponseCreators.withSuccess("""
                {"choices":[{"finish_reason":"stop","message":{"content":"핵심 개념은 제네릭입니다","refusal":null}}]}""", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("모델이 거부하면 기술적 실패다")
    void absorbsRefusal() {
        assertTechnicalFailure(MockRestResponseCreators.withSuccess("""
                {"choices":[{"finish_reason":"stop","message":{"content":null,"refusal":"거부"}}]}""", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("출력이 잘리면(finish_reason=length) 기술적 실패다")
    void absorbsTruncatedOutput() {
        assertTechnicalFailure(MockRestResponseCreators.withSuccess("""
                {"choices":[{"finish_reason":"length","message":{"content":"{\\"concept\\":\\"제","refusal":null}}]}""", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("개념이 25자를 넘으면 계약 위반이다 — 근거가 없는 것과 다르다")
    void treatsTooLongConceptAsContractViolation() {
        assertTechnicalFailure(MockRestResponseCreators.withSuccess(
                chatResponse("제네릭 와일드카드의 상한 경계와 하한 경계가 가지는 의미와 차이점", "0.9", EVIDENCE), MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("신뢰도가 0~1 밖이면 계약 위반이다")
    void treatsConfidenceOutOfRangeAsContractViolation() {
        assertTechnicalFailure(MockRestResponseCreators.withSuccess(
                chatResponse("제네릭 와일드카드", "5", EVIDENCE), MediaType.APPLICATION_JSON));
        assertTechnicalFailure(MockRestResponseCreators.withSuccess(
                chatResponse("제네릭 와일드카드", "-0.5", EVIDENCE), MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("필수 필드가 빠지면 계약 위반이다 — 빠진 신뢰도를 0 으로 읽으면 근거 없음과 구분할 수 없다")
    void treatsMissingFieldsAsContractViolation() {
        assertTechnicalFailure(MockRestResponseCreators.withSuccess("""
                {"choices":[{"finish_reason":"stop","message":{"content":"{\\"concept\\":\\"제네릭\\"}","refusal":null}}]}""", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("빈 개념인데 신뢰도·근거가 남아 있으면 계약 위반이다 — 지시는 셋을 함께 비우라고 했다")
    void treatsInconsistentEmptyAnswerAsContractViolation() {
        assertTechnicalFailure(
                MockRestResponseCreators.withSuccess(chatResponse("", "0.9", ""), MediaType.APPLICATION_JSON));
        assertTechnicalFailure(
                MockRestResponseCreators.withSuccess(chatResponse("", "0", EVIDENCE), MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("choices 가 비면 기술적 실패다")
    void absorbsEmptyChoices() {
        assertTechnicalFailure(MockRestResponseCreators.withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("401·402·429·5xx·timeout 은 모두 기술적 실패다")
    void absorbsTransportFailures() {
        assertTechnicalFailure(
                MockRestResponseCreators.withUnauthorizedRequest().body("{\"message\":\"invalid key\"}"));
        assertTechnicalFailure(MockRestResponseCreators.withStatus(HttpStatus.PAYMENT_REQUIRED)
                .body("{\"message\":\"credit exhausted\"}"));
        assertTechnicalFailure(MockRestResponseCreators.withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .body("{\"message\":\"slow down\"}"));
        assertTechnicalFailure(MockRestResponseCreators.withServerError().body("{\"message\":\"unavailable\"}"));
        assertTechnicalFailure(request -> {
            throw new SocketTimeoutException("read timed out");
        });
    }

    /** 값은 오지만 쓸 수 없다 — 파이프라인이 {@code LOW_CONFIDENCE} 로 다룰 상태. */
    private void assertGroundless(String responseBody) {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(MockRestResponseCreators.withSuccess(responseBody, MediaType.APPLICATION_JSON));

        TipConcept concept = fixture.adapter().extract(request()).orElseThrow();

        assertThat(concept.isUsable()).isFalse();
        assertThat(concept.confidence()).isZero();
    }

    /** 빈 값 — 파이프라인이 {@code TIP_GENERATION_FAILED} 로 다룰 상태. */
    private void assertTechnicalFailure(ResponseCreator response) {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(CHAT_URL)).andRespond(response);

        assertThat(fixture.adapter().extract(request())).isEmpty();
    }
}
