package com.a105.zani.postclass.infrastructure.gms;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import com.a105.zani.common.infrastructure.gms.GmsProperties;
import com.a105.zani.postclass.application.analyzestudents.ConceptSection;
import com.a105.zani.postclass.application.analyzestudents.SessionAnalysisContext;
import com.a105.zani.postclass.application.analyzestudents.StudentObservations;
import com.a105.zani.postclass.application.port.StudentAnalysis;
import com.a105.zani.postclass.application.port.StudentAnalysisRequest;
import com.a105.zani.postclass.infrastructure.config.StudentAnalysisProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * 학생별 분석 어댑터의 요청 형식과 응답 처리를 확인한다.
 *
 * <p>실제 GMS 를 때리지 않는다. 내가 정한 응답을 내가 파싱하는 것이라 프롬프트와 스키마가 실제 모델에서 통하는지는 알려 주지 않는다 — 그 확인은 수동 스모크의 몫이다.
 */
class GmsStudentAnalysisHttpAdapterTest {

    private static final String BASE_URL = "https://gms.test";
    private static final String CHAT_URL = BASE_URL + "/v1/chat/completions";

    private static final long SESSION_ID = 7_311_064_012_345_678L;
    private static final long PARTICIPANT_ID = 7_311_064_087_654_321L;

    @Test
    void sendsAliasOnlyWithMaxCompletionTokensAndStrictSchema() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("max_completion_tokens")))
                .andExpect(content().string(not(containsString("\"max_tokens\""))))
                .andExpect(content().string(containsString("json_schema")))
                // 근거 유형 다섯 가지가 스키마 enum 과 프롬프트에 함께 실려야 한다. 좁힌 REPEAT 는 없어야 한다.
                .andExpect(content().string(containsString("NO_RESPONSE")))
                .andExpect(content().string(containsString("LOW_ENGAGEMENT")))
                .andExpect(content().string(not(containsString("REPEAT"))))
                .andExpect(content().string(containsString("student-001")))
                .andExpect(content().string(not(containsString(String.valueOf(SESSION_ID)))))
                .andExpect(content().string(not(containsString(String.valueOf(PARTICIPANT_ID)))))
                .andRespond(
                        MockRestResponseCreators.withSuccess(chatResponse(analysisJson()), MediaType.APPLICATION_JSON));

        Optional<StudentAnalysis> analysis = fixture.adapter().analyze(request());

        assertThat(analysis).isPresent();
        assertThat(analysis.get().participationSummary()).isEqualTo("참여도 요약");
        assertThat(analysis.get().questionCount()).isEqualTo(2);
        assertThat(analysis.get().quiz().questions())
                .extracting(StudentAnalysis.QuestionDraft::sectionIndex)
                .containsOnly(1);
        assertThat(analysis.get().recommendations())
                .extracting(
                        StudentAnalysis.RecommendationDraft::sectionIndex, StudentAnalysis.RecommendationDraft::type)
                .containsExactly(Tuple.tuple(1, "CONFUSED"));
        assertThat(analysis.get().quiz().questions()).hasSize(3);
        assertThat(analysis.get().quiz().questions().getFirst().options()).hasSize(4);
        assertThat(analysis.get().quiz().questions().getFirst().options())
                .extracting(StudentAnalysis.OptionDraft::correct)
                .containsExactly(true, false, false, false);
        fixture.server().verify();
    }

    @Test
    void returnsEmptyWhenResponseWasTruncated() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(MockRestResponseCreators.withSuccess("""
                        {"choices":[{"finish_reason":"length","message":{"content":"{\\"participationSummary\\":\\"잘","refusal":null}}]}""", MediaType.APPLICATION_JSON));

        assertThat(fixture.adapter().analyze(request())).isEmpty();
    }

    @Test
    void returnsEmptyWhenModelRefused() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(MockRestResponseCreators.withSuccess("""
                        {"choices":[{"finish_reason":"stop","message":{"content":null,"refusal":"거절"}}]}""", MediaType.APPLICATION_JSON));

        assertThat(fixture.adapter().analyze(request())).isEmpty();
    }

    @Test
    void returnsEmptyWhenBodyLeavesTheSchema() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse("{\\\"participationSummary\\\":\\\"요약\\\"}"), MediaType.APPLICATION_JSON));

        // quiz 가 없는 응답은 저장할 수 없다. 스키마 위반은 기술적 실패다.
        assertThat(fixture.adapter().analyze(request())).isEmpty();
    }

    @Test
    void returnsEmptyOnTimeout() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(CHAT_URL)).andRespond(request -> {
            throw new IOException(new SocketTimeoutException("read timed out"));
        });

        assertThat(fixture.adapter().analyze(request())).isEmpty();
    }

    @Test
    void returnsEmptyOnCreditExhaustion() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.PAYMENT_REQUIRED));

        assertThat(fixture.adapter().analyze(request())).isEmpty();
    }

    private record Fixture(GmsStudentAnalysisHttpAdapter adapter, MockRestServiceServer server) {}

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GmsStudentAnalysisHttpAdapter adapter = new GmsStudentAnalysisHttpAdapter(
                builder.build(), JsonMapper.builder().build(), gmsProperties(), new StudentAnalysisProperties(3_000));
        return new Fixture(adapter, server);
    }

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
                Duration.ofSeconds(6),
                "gpt-5.4-mini",
                Duration.ofSeconds(60));
    }

    /** 요청에 식별자가 들어가지 않는지 보려면 세션·참여자 ID 가 실제로 흐르는 경로를 태워야 한다. */
    private StudentAnalysisRequest request() {
        SessionAnalysisContext context = new SessionAnalysisContext(
                "이차방정식 수업 " + SESSION_ID % 10, "공통 요약", List.of(new ConceptSection(1, "근의 공식", "설명", 0L, 60_000L)));
        StudentObservations observations = new StudentObservations(
                List.of(new StudentObservations.Attention("NOT_ENGAGED", 10_000L)),
                List.of(new StudentObservations.Prompt("CONFUSED", 20_000L)),
                List.of(30_000L),
                List.of(new StudentObservations.Chat("질문이요", 40_000L)));
        return StudentAnalysisRequest.raw("student-001", context, observations);
    }

    private static String chatResponse(String escapedContent) {
        return """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"%s","refusal":null}}]}""".formatted(escapedContent);
    }

    /** 모델이 스키마대로 낸 본문. chat completions 의 content 는 문자열이라 한 번 이스케이프한다. */
    private static String analysisJson() {
        String option = "{\\\"optionText\\\":\\\"%s\\\",\\\"correct\\\":%s}";
        String options = String.join(
                ",",
                option.formatted("정답", "true"),
                option.formatted("오답1", "false"),
                option.formatted("오답2", "false"),
                option.formatted("오답3", "false"));
        String question = "{\\\"sectionIndex\\\":1,\\\"questionText\\\":\\\"문항\\\",\\\"explanation\\\":\\\"해설\\\","
                + "\\\"options\\\":[" + options + "]}";
        return "{\\\"participationSummary\\\":\\\"참여도 요약\\\",\\\"questionCount\\\":2,"
                + "\\\"recommendations\\\":[{\\\"sectionIndex\\\":1,\\\"type\\\":\\\"CONFUSED\\\","
                + "\\\"title\\\":\\\"근의 공식\\\",\\\"description\\\":\\\"다시 보기\\\"}],"
                + "\\\"quiz\\\":{\\\"title\\\":\\\"퀴즈\\\",\\\"description\\\":\\\"설명\\\",\\\"questions\\\":["
                + String.join(",", question, question, question) + "]}}";
    }
}
