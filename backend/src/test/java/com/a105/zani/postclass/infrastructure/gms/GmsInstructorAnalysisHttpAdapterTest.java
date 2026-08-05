package com.a105.zani.postclass.infrastructure.gms;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import com.a105.zani.common.infrastructure.gms.GmsProperties;
import com.a105.zani.postclass.application.analyzeinstructor.ConceptSection;
import com.a105.zani.postclass.application.analyzeinstructor.InstructorAnalysisContext;
import com.a105.zani.postclass.application.analyzeinstructor.PublicChat;
import com.a105.zani.postclass.application.port.InstructorAnalysis;
import com.a105.zani.postclass.application.port.InstructorAnalysisRequest;
import com.a105.zani.postclass.infrastructure.config.InstructorAnalysisProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/** 요청 형식과 응답 검증을 확인한다. 기술적 실패·refusal·절단·스키마 위반이 모두 빈 값 하나로 접힌다. */
class GmsInstructorAnalysisHttpAdapterTest {

    private static final String BASE_URL = "https://gms.test";
    private static final String CHAT_URL = BASE_URL + "/v1/chat/completions";

    private record Fixture(GmsInstructorAnalysisHttpAdapter adapter, MockRestServiceServer server) {}

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GmsInstructorAnalysisHttpAdapter adapter = new GmsInstructorAnalysisHttpAdapter(
                builder.build(),
                JsonMapper.builder().build(),
                gmsProperties(),
                new InstructorAnalysisProperties(3_000));
        return new Fixture(adapter, server);
    }

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
                Duration.ofSeconds(60));
    }

    private static InstructorAnalysisRequest request() {
        return InstructorAnalysisRequest.of(new InstructorAnalysisContext(
                "상태 관리 수업",
                "수업 공통 요약",
                List.of(new ConceptSection(1, "상태 관리 개요", "구간 요약", 0L, 519_000L)),
                List.of(),
                List.of(),
                List.of(),
                List.of(new PublicChat(120_000L, "리렌더링이 왜 일어나나요?")),
                "예외 처리 설명이 급했다"));
    }

    /** 모델이 스키마대로 낸 본문을 chat completions 응답 봉투에 넣는다. */
    private static String chatResponse(String innerJson, String finishReason) {
        String escaped = innerJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"choices\":[{\"message\":{\"content\":\"%s\",\"refusal\":null},\"finish_reason\":\"%s\"}]}"
                .formatted(escaped, finishReason);
    }

    private static String validInner() {
        return "{\"questionCount\":12,"
                + "\"overallFeedback\":\"전체 흐름은 개념에서 해법 순으로 잘 짜여 있었습니다.\","
                + "\"scores\":{\"delivery\":88,\"structureFlow\":84,\"interaction\":71,\"difficultyControl\":76},"
                + "\"insights\":[{\"sectionIndex\":1,\"title\":\"어려운 구간 보강\","
                + "\"evidence\":\"확인 필요 신호와 공개 질문이 같은 구간에 모였습니다.\","
                + "\"suggestion\":\"실습 시간을 늘려보세요.\","
                + "\"evidenceKinds\":[\"ATTENTION_FLOW\",\"CHAT\"]}]}";
    }

    @Test
    @DisplayName("모델 응답을 포트 타입으로 옮긴다")
    void maps_a_valid_response() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse(validInner(), "stop"), MediaType.APPLICATION_JSON));

        InstructorAnalysis analysis = fixture.adapter().analyze(request()).orElseThrow();

        assertThat(analysis.questionCount()).isEqualTo(12);
        assertThat(analysis.overallFeedback()).startsWith("전체 흐름은");
        assertThat(analysis.scores()).isEqualTo(new InstructorAnalysis.Scores(88, 84, 71, 76));
        assertThat(analysis.insights()).hasSize(1);
        assertThat(analysis.insights().getFirst().evidenceKinds()).containsExactly("ATTENTION_FLOW", "CHAT");
        fixture.server().verify();
    }

    @Test
    @DisplayName("요청에 max_completion_tokens·strict 스키마·근거 하한 2 가 실린다")
    void sends_the_documented_request_shape() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andExpect(content().string(containsString("\"max_completion_tokens\":3000")))
                .andExpect(content().string(containsString("\"strict\":true")))
                .andExpect(content().string(containsString("\"additionalProperties\":false")))
                .andExpect(content().string(containsString("\"minItems\":2")))
                .andExpect(content().string(not(containsString("\"max_tokens\""))))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse(validInner(), "stop"), MediaType.APPLICATION_JSON));

        assertThat(fixture.adapter().analyze(request())).isPresent();
        fixture.server().verify();
    }

    @Test
    @DisplayName("본문에 식별자를 싣지 않는다 — 요청 타입 자체가 담지 못한다")
    void never_sends_identifiers() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andExpect(content().string(not(containsString("sessionId"))))
                .andExpect(content().string(not(containsString("participantId"))))
                .andExpect(content().string(not(containsString("@"))))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse(validInner(), "stop"), MediaType.APPLICATION_JSON));

        assertThat(fixture.adapter().analyze(request())).isPresent();
        fixture.server().verify();
    }

    @Test
    @DisplayName("refusal 은 빈 값이다")
    void a_refusal_is_empty() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(MockRestResponseCreators.withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":null,\"refusal\":\"거부\"},"
                                + "\"finish_reason\":\"stop\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(fixture.adapter().analyze(request())).isEmpty();
    }

    @Test
    @DisplayName("finish_reason=length 는 빈 값이다 — JSON 이 잘려 파싱도 실패한다")
    void a_truncated_response_is_empty() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse(validInner(), "length"), MediaType.APPLICATION_JSON));

        assertThat(fixture.adapter().analyze(request())).isEmpty();
    }

    @Test
    @DisplayName("스키마 밖 본문은 빈 값이다")
    void a_schema_violation_is_empty() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse("{\"unexpected\":true}", "stop"), MediaType.APPLICATION_JSON));

        assertThat(fixture.adapter().analyze(request())).isEmpty();
    }

    @Test
    @DisplayName("종합 피드백이 비었으면 빈 값이다 — 저장할 것이 없다")
    void a_blank_feedback_is_empty() {
        Fixture fixture = fixture();
        String inner = validInner().replace("전체 흐름은 개념에서 해법 순으로 잘 짜여 있었습니다.", "   ");
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(
                        MockRestResponseCreators.withSuccess(chatResponse(inner, "stop"), MediaType.APPLICATION_JSON));

        assertThat(fixture.adapter().analyze(request())).isEmpty();
    }

    @Test
    @DisplayName("점수 하나가 빠지면 빈 값이다 — 화면이 도넛 4개를 그린다")
    void a_partial_score_set_is_empty() {
        Fixture fixture = fixture();
        String inner = validInner().replace("\"difficultyControl\":76", "\"difficultyControl\":null");
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(
                        MockRestResponseCreators.withSuccess(chatResponse(inner, "stop"), MediaType.APPLICATION_JSON));

        assertThat(fixture.adapter().analyze(request())).isEmpty();
    }

    @Test
    @DisplayName("5xx 는 빈 값이다 — 재시도하지 않는다")
    void a_server_error_is_empty() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(CHAT_URL)).andRespond(MockRestResponseCreators.withServerError());

        assertThat(fixture.adapter().analyze(request())).isEmpty();
        fixture.server().verify();
    }

    @Test
    @DisplayName("402(크레딧 소진)도 같은 빈 값이다")
    void a_payment_required_is_empty() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.PAYMENT_REQUIRED));

        assertThat(fixture.adapter().analyze(request())).isEmpty();
    }

    @Test
    @DisplayName("음수 질문 수는 0 으로 접는다 — 스키마 강제 여부가 미확인이다")
    void clamps_a_negative_question_count() {
        Fixture fixture = fixture();
        String inner = validInner().replace("\"questionCount\":12", "\"questionCount\":-3");
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(
                        MockRestResponseCreators.withSuccess(chatResponse(inner, "stop"), MediaType.APPLICATION_JSON));

        assertThat(fixture.adapter().analyze(request()).orElseThrow().questionCount())
                .isZero();
    }

    @Test
    @DisplayName("구간 번호가 없는 인사이트는 -1 로 접는다 — 0 은 전체 수업이라는 뜻이 있다")
    void a_missing_section_index_becomes_minus_one() {
        Fixture fixture = fixture();
        String inner = validInner().replace("\"sectionIndex\":1", "\"sectionIndex\":null");
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(
                        MockRestResponseCreators.withSuccess(chatResponse(inner, "stop"), MediaType.APPLICATION_JSON));

        assertThat(fixture.adapter()
                        .analyze(request())
                        .orElseThrow()
                        .insights()
                        .getFirst()
                        .sectionIndex())
                .isEqualTo(-1);
    }
}
