package com.a105.zani.report.infrastructure.gms;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import com.a105.zani.common.infrastructure.gms.GmsProperties;
import com.a105.zani.report.application.port.AnchoredSection;
import com.a105.zani.report.application.port.AnswerCitation;
import com.a105.zani.report.application.port.AnswerLine;
import com.a105.zani.report.application.port.HistoryTurn;
import com.a105.zani.report.application.port.OutlineEntry;
import com.a105.zani.report.application.port.ReportAnswerFailure;
import com.a105.zani.report.application.port.ReportAnswerOutcome;
import com.a105.zani.report.application.port.ReportAnswerRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * 질의응답 어댑터의 요청 형식과 인용 검증을 확인한다.
 *
 * <p>인용 검증이 이 어댑터의 핵심이다. S15P11A105-329 에서 모델이 타임스탬프를 지어낸 전례가 있고, 그 값이 그대로 화면에 나가면 학생이 누른 이동 버튼이 엉뚱한 곳으로 간다.
 */
class GmsReportAnswerHttpAdapterTest {

    private static final String BASE_URL = "https://gms.test";
    private static final String CHAT_URL = BASE_URL + "/v1/chat/completions";
    private static final long WINDOW_FROM_MS = 60_000L;
    private static final long WINDOW_TO_MS = 185_000L;

    private static GmsProperties gmsProperties() {
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
                Duration.ofSeconds(60),
                Duration.ofSeconds(25));
    }

    private record Fixture(GmsReportAnswerHttpAdapter adapter, MockRestServiceServer server) {}

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GmsReportAnswerHttpAdapter adapter = new GmsReportAnswerHttpAdapter(
                builder.build(), JsonMapper.builder().build(), gmsProperties(), new ReportAnswerProperties(1_500));
        return new Fixture(adapter, server);
    }

    private static ReportAnswerRequest request() {
        return request(List.of());
    }

    private static ReportAnswerRequest request(List<HistoryTurn> history) {
        return new ReportAnswerRequest(
                new AnchoredSection("해시 충돌 해결", "체이닝과 개방주소법을 다뤘다.", 95_000L, 140_000L),
                List.of(
                        new AnswerLine("instructor", 63_000L, "해시 테이블은 키를 이용해 데이터를 저장합니다."),
                        new AnswerLine("instructor", 92_000L, "이것을 해시 충돌이라고 합니다."),
                        new AnswerLine("instructor", 98_000L, "체이닝과 개방주소법이 있습니다.")),
                List.of(new OutlineEntry("적재율과 배열 확장", 140_000L, 185_000L)),
                List.of(),
                "개방주소법이 뭐야?",
                "개방주소법",
                history,
                WINDOW_FROM_MS,
                WINDOW_TO_MS);
    }

    /** 모델이 스키마대로 낸 본문을 chat completions 응답 봉투에 넣는다. */
    private static String chatResponse(String inner) {
        return """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"%s","refusal":null}}]}""".formatted(inner.replace("\"", "\\\""));
    }

    private static String answer(String citations) {
        return "{\"answer\":\"충돌이 나면 빈 자리를 찾아 넣는 방식입니다.\",\"grounded\":true,\"citations\":[%s]}".formatted(citations);
    }

    private void respondWith(Fixture fixture, String body) {
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(MockRestResponseCreators.withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void sendsAStructuredOutputRequestWithoutRealNames() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("\"model\":\"gpt-5.4-mini\"")))
                .andExpect(content().string(containsString("\"max_completion_tokens\":1500")))
                .andExpect(content().string(containsString("\"strict\":true")))
                // max_tokens 는 게이트웨이가 400 으로 거부한다(GMS 가이드 §6).
                .andExpect(content().string(not(containsString("\"max_tokens\""))))
                // 별칭만 나간다. 실명이 섞이면 가이드 §9 위반이라 여기서 막는다.
                .andExpect(content().string(containsString("instructor")))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse(answer("{\"offsetMs\":92000}")), MediaType.APPLICATION_JSON));

        ReportAnswerOutcome outcome = fixture.adapter().answer(request());

        assertThat(outcome.failed()).isFalse();
        fixture.server().verify();
    }

    @Test
    void fillsTheQuoteFromOurOwnTranscript() {
        Fixture fixture = fixture();
        respondWith(fixture, chatResponse(answer("{\"offsetMs\":92000}")));

        ReportAnswerOutcome outcome = fixture.adapter().answer(request());

        // 인용문을 모델에게 받지 않으므로 지어낸 문장이 들어올 자리가 없다. 시각으로 우리 전사를 찾아 채운다.
        assertThat(outcome.value()).isPresent();
        assertThat(outcome.value().get().citations())
                .extracting(AnswerCitation::offsetMs, AnswerCitation::quote)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(92_000L, "이것을 해시 충돌이라고 합니다."));
    }

    @Test
    void snapsAMidUtteranceInstantBackToTheUtteranceStart() {
        Fixture fixture = fixture();
        respondWith(fixture, chatResponse(answer("{\"offsetMs\":95500}")));

        ReportAnswerOutcome outcome = fixture.adapter().answer(request());

        // 발화 중간을 짚어도 그 발화로 붙는다. 뒤로 당기면 아직 하지 않은 말을 근거로 삼게 된다.
        assertThat(outcome.value().get().citations())
                .extracting(AnswerCitation::offsetMs)
                .containsExactly(92_000L);
    }

    @Test
    void dropsOnlyTheCitationThatFellOutsideTheSentWindow() {
        Fixture fixture = fixture();
        respondWith(fixture, chatResponse(answer("{\"offsetMs\":22000},{\"offsetMs\":92000}")));

        ReportAnswerOutcome outcome = fixture.adapter().answer(request());

        // 깨진 인용 하나가 답변 전체를 죽이지 않는다. 이동 버튼 하나가 없는 편이 답을 통째로 잃는 것보다 낫다.
        assertThat(outcome.value()).isPresent();
        assertThat(outcome.value().get().answer()).isNotEmpty();
        assertThat(outcome.value().get().citations())
                .extracting(AnswerCitation::offsetMs)
                .containsExactly(92_000L);
    }

    @Test
    void dropsACitationThatPointsBeforeTheFirstUtterance() {
        Fixture fixture = fixture();
        respondWith(fixture, chatResponse(answer("{\"offsetMs\":61000}")));

        ReportAnswerOutcome outcome = fixture.adapter().answer(request());

        // 창 안이지만 그 시각에 아무도 말하지 않았다. 붙일 발화가 없으면 버린다.
        assertThat(outcome.value().get().citations()).isEmpty();
        assertThat(outcome.value().get().answer()).isNotEmpty();
    }

    @Test
    void keepsTheSameUtteranceOnlyOnce() {
        Fixture fixture = fixture();
        respondWith(fixture, chatResponse(answer("{\"offsetMs\":92000},{\"offsetMs\":93000}")));

        ReportAnswerOutcome outcome = fixture.adapter().answer(request());

        // 둘 다 같은 발화로 스냅된다. 화면에 같은 이동 버튼이 두 개 생기지 않게 뒤엣것을 버린다.
        assertThat(outcome.value().get().citations()).hasSize(1);
    }

    @Test
    void rejectsAnAnswerLongerThanTheContract() {
        Fixture fixture = fixture();
        String tooLong = "가".repeat(601);
        respondWith(fixture, chatResponse("{\"answer\":\"%s\",\"grounded\":true,\"citations\":[]}".formatted(tooLong)));

        ReportAnswerOutcome outcome = fixture.adapter().answer(request());

        // strict 스키마가 maxLength 를 강제하는지 실측되지 않았다(GMS 가이드 §11). 서버에서 다시 본다.
        assertThat(outcome.failure()).isEqualTo(ReportAnswerFailure.UNUSABLE_RESPONSE);
    }

    @Test
    void rejectsATruncatedResponse() {
        Fixture fixture = fixture();
        respondWith(fixture, """
                {"choices":[{"finish_reason":"length","message":{"role":"assistant","content":"{","refusal":null}}]}""");

        ReportAnswerOutcome outcome = fixture.adapter().answer(request());

        // length 면 JSON 이 잘려 파싱도 실패한다. max_completion_tokens 를 의심할 수 있게 사유를 나눈다.
        assertThat(outcome.failure()).isEqualTo(ReportAnswerFailure.UNUSABLE_RESPONSE);
    }

    @Test
    void treatsARefusalAsUnusableRatherThanUnavailable() {
        Fixture fixture = fixture();
        respondWith(fixture, """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":null,"refusal":"거절"}}]}""");

        ReportAnswerOutcome outcome = fixture.adapter().answer(request());

        // 다시 눌러도 같은 거절이 온다(temperature 0). UNAVAILABLE 로 두면 화면이 재시도를 권한다.
        assertThat(outcome.failure()).isEqualTo(ReportAnswerFailure.UNUSABLE_RESPONSE);
    }

    @Test
    void treatsAGatewayErrorAsUnavailable() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(CHAT_URL)).andRespond(MockRestResponseCreators.withServerError());

        ReportAnswerOutcome outcome = fixture.adapter().answer(request());

        assertThat(outcome.failure()).isEqualTo(ReportAnswerFailure.UNAVAILABLE);
    }

    @Test
    void dropsTheOldestHistoryTurnsBeforeGivingUpOnTheBudget() {
        Fixture fixture = fixture();
        // 예산(92,160B)을 혼자 넘기는 이력이다. 전사를 솎아 내면 질문한 지점의 근거가 사라지므로 이력부터 버린다.
        List<HistoryTurn> history =
                List.of(new HistoryTurn("user", "가".repeat(40_000)), new HistoryTurn("assistant", "짧은 답"));
        respondWith(fixture, chatResponse(answer("{\"offsetMs\":92000}")));

        ReportAnswerOutcome outcome = fixture.adapter().answer(request(history));

        // 이력을 줄여 예산에 맞췄으므로 호출은 성공하고 전사는 그대로 남는다.
        assertThat(outcome.failed()).isFalse();
        fixture.server().verify();
    }

    @Test
    void doesNotSendABodyThatTheGatewayWouldSilentlyTruncate() {
        Fixture fixture = fixture();
        ReportAnswerRequest huge = new ReportAnswerRequest(
                new AnchoredSection("거대 구간", "요약", 0L, 1_000L),
                List.of(new AnswerLine("instructor", 100L, "가".repeat(40_000))),
                List.of(),
                List.of(),
                "질문",
                null,
                List.of(),
                0L,
                1_000L);

        ReportAnswerOutcome outcome = fixture.adapter().answer(huge);

        // 보내면 게이트웨이가 본문을 잘라 "Model not found" 로 답해 원인을 알 수 없는 실패가 된다(가이드 §4.1).
        assertThat(outcome.failure()).isEqualTo(ReportAnswerFailure.REQUEST_TOO_LARGE);
        fixture.server().verify();
    }
}
