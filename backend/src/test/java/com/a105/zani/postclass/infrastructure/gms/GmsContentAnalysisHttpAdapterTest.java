package com.a105.zani.postclass.infrastructure.gms;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import com.a105.zani.common.infrastructure.gms.GmsProperties;
import com.a105.zani.postclass.application.port.ContentAnalysisFailure;
import com.a105.zani.postclass.application.port.ContentAnalysisLine;
import com.a105.zani.postclass.application.port.ContentAnalysisOutcome;
import com.a105.zani.postclass.application.port.ContentAnalysisRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * 공통 분석 어댑터의 요청 형식과 응답 검증을 확인한다.
 *
 * <p>서버에서 다시 검증하는 이유: strict 스키마가 {@code maxLength}·{@code minimum} 을 실제로 강제하는지 실측으로 확인하지 못했다(GMS 가이드 §11). 계약을 벗어난 응답은
 * 저장하지 않고 빈 값으로 돌려, 파이프라인이 실패로 기록하게 한다.
 */
class GmsContentAnalysisHttpAdapterTest {

    private static final String BASE_URL = "https://gms.test";
    private static final String CHAT_URL = BASE_URL + "/v1/chat/completions";
    private static final long CLASS_DURATION_MS = 45 * 60 * 1_000L;

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

    private record Fixture(GmsContentAnalysisHttpAdapter adapter, MockRestServiceServer server) {}

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GmsContentAnalysisHttpAdapter adapter = new GmsContentAnalysisHttpAdapter(
                builder.build(), JsonMapper.builder().build(), gmsProperties(), new ContentAnalysisProperties(12_000));
        return new Fixture(adapter, server);
    }

    private static ContentAnalysisRequest request() {
        return new ContentAnalysisRequest(
                CLASS_DURATION_MS,
                List.of(new ContentAnalysisLine("instructor", 2_000, 32_000, "자, 오늘은 React 의 상태 관리를 다뤄보겠습니다.")));
    }

    /** 모델이 스키마대로 낸 본문을 chat completions 응답 봉투에 넣는다. */
    private static String chatResponse(String inner) {
        return """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"%s","refusal":null}}]}""".formatted(inner.replace("\"", "\\\""));
    }

    private static String sections(String sections) {
        return "{\"classSummary\":\"React 상태 관리를 다뤘다.\",\"sections\":[%s]}".formatted(sections);
    }

    private static String section(String title, long startOffsetMs, long endOffsetMs) {
        return "{\"title\":\"%s\",\"summary\":\"구간 요약입니다.\",\"startOffsetMs\":%d,\"endOffsetMs\":%d}"
                .formatted(title, startOffsetMs, endOffsetMs);
    }

    @Test
    void returnsTheTimelineAndSendsStructuredOutputRequest() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("\"model\":\"gpt-5.4-mini\"")))
                .andExpect(content().string(containsString("\"temperature\":0")))
                // gpt-5.4-mini 는 max_tokens 를 거부한다(실측). 회귀로 못 박는다.
                .andExpect(content().string(containsString("\"max_completion_tokens\":12000")))
                .andExpect(content().string(not(containsString("\"max_tokens\""))))
                .andExpect(content().string(containsString("json_schema")))
                .andExpect(content().string(containsString("session_content_analysis")))
                // 수업 길이를 함께 보내 모델이 구간 끝을 범위 안으로 내게 한다.
                .andExpect(content().string(containsString("classDurationMs")))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse(sections(section("상태 관리", 0, 600_000))), MediaType.APPLICATION_JSON));

        ContentAnalysisOutcome outcome = fixture.adapter().analyze(request());

        assertThat(outcome.value()).isPresent();
        assertThat(outcome.analysis().classSummary()).isEqualTo("React 상태 관리를 다뤘다.");
        assertThat(outcome.analysis().sections()).hasSize(1);
        assertThat(outcome.analysis().sections().getFirst().title()).isEqualTo("상태 관리");
    }

    /** 세션 식별자를 보내지 않는다(GMS 가이드 §9). 전사 본문과 별칭·시각만 나간다. */
    @Test
    void sendsNoSessionIdentifier() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andExpect(content().string(not(containsString("sessionId"))))
                .andExpect(content().string(not(containsString("sessionParticipantId"))))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse(sections(section("상태 관리", 0, 600_000))), MediaType.APPLICATION_JSON));

        assertThat(fixture.adapter().analyze(request()).value()).isPresent();
    }

    /**
     * 화자 별칭은 실어 보내고 수업 제목은 보내지 않는다.
     *
     * <p>별칭은 강사 설명과 학생 질문을 가르는 데 필요하고 되돌릴 수 없는 값이다(가이드 §9.2). 수업 제목은 강사 자유 입력이라 실명·이메일이 섞일 수 있어 §9.3 에 걸린다 —
     * {@code ContentAnalysisRequest} 에 필드를 두지 않았지만, 어댑터가 다른 경로로 제목을 넣지 않는지도 본문에서 확인한다.
     */
    @Test
    void sendsSpeakerAliasesButNoLectureTitle() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                // 전사는 user 메시지 안에 JSON 문자열로 들어가 한 번 더 escape 된다.
                .andExpect(content().string(containsString("\\\"speaker\\\":\\\"instructor\\\"")))
                .andExpect(content().string(not(containsString("lectureTitle"))))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse(sections(section("상태 관리", 0, 600_000))), MediaType.APPLICATION_JSON));

        assertThat(fixture.adapter().analyze(request()).value()).isPresent();
        fixture.server().verify();
    }

    /**
     * 평가 금지 지시가 프롬프트에 실리고, 스키마에 평가 필드가 없는지.
     *
     * <p>FRD §17.4 는 감정·성격·역량 평가를 금지한다. 스키마에 필드가 없어도 요약 문장에는 들어갈 수 있어 지시가 함께 필요하다. Jackson 은 모르는 필드를 조용히 버리므로, 지시가
     * 사라지거나 스키마에 평가 필드가 붙어도 다른 테스트는 깨지지 않는다.
     */
    @Test
    void forbidsEvaluationInThePromptAndTheSchema() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andExpect(content().string(containsString("성격, 태도, 성실성, 감정, 역량을 평가하지 않는다")))
                // 스키마 속성은 정확히 네 개다. 평가 필드가 붙으면 여기서 걸린다.
                .andExpect(content().string(containsString("startOffsetMs")))
                .andExpect(content().string(not(containsString("emotion"))))
                .andExpect(content().string(not(containsString("competency"))))
                .andExpect(content().string(not(containsString("personality"))))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse(sections(section("상태 관리", 0, 600_000))), MediaType.APPLICATION_JSON));

        assertThat(fixture.adapter().analyze(request()).value()).isPresent();
    }

    /**
     * 순서만 어긋난 응답은 정렬해 되살린다.
     *
     * <p>{@code temperature: 0} 이라 재시도해도 같은 순서가 온다. 여기서 정렬하지 않으면 적재 애그리거트가 거절해 그 세션은 리포트를 영영 받지 못한다. 진짜 겹침은 그대로 거절된다.
     */
    @Test
    void sortsSectionsThatTheModelReturnedOutOfOrder() {
        Fixture fixture = fixture();
        String unordered = section("뒤 구간", 600_000, 900_000) + "," + section("앞 구간", 0, 600_000);
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse(sections(unordered)), MediaType.APPLICATION_JSON));

        ContentAnalysisOutcome outcome = fixture.adapter().analyze(request());

        assertThat(outcome.value()).isPresent();
        assertThat(outcome.analysis().sections().getFirst().title()).isEqualTo("앞 구간");
        assertThat(outcome.analysis().sections().getLast().title()).isEqualTo("뒤 구간");
    }

    /**
     * 구간 사이의 빈 시간을 앞 구간에 붙인다.
     *
     * <p>프롬프트로는 안정적으로 되지 않는다 — 같은 요청에 한 번은 이어지고 한 번은 발화 순간만 덮는 구간이 왔다(실측). 공백이 남으면 수업 중간 시각이 어느 구간에도 속하지 않아 집중도 그래프와 클립
     * 타임스탬프가 가리킬 구간을 찾지 못한다. 앞뒤 끝은 늘리지 않는다 — 무음에 주제 라벨을 붙이는 쪽이 더 틀린 값이다.
     */
    @Test
    void fillsGapsBetweenSectionsSoTheTimelineIsContinuous() {
        Fixture fixture = fixture();
        String gapped = section("앞 구간", 2_000, 32_000) + "," + section("뒤 구간", 195_000, 226_000);
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse(sections(gapped)), MediaType.APPLICATION_JSON));

        ContentAnalysisOutcome outcome = fixture.adapter().analyze(request());

        assertThat(outcome.value()).isPresent();
        // 앞 구간의 끝이 뒤 구간의 시작까지 늘어난다.
        assertThat(outcome.analysis().sections().getFirst().startOffsetMs()).isEqualTo(2_000);
        assertThat(outcome.analysis().sections().getFirst().endOffsetMs()).isEqualTo(195_000);
        // 마지막 구간의 끝과 첫 구간의 시작은 모델이 준 값 그대로다.
        assertThat(outcome.analysis().sections().getLast().endOffsetMs()).isEqualTo(226_000);
    }

    /**
     * 빈틈 메우기가 진짜 겹침을 지우지 않는지.
     *
     * <p>다음 구간이 이 구간의 끝보다 앞에서 시작하면 겹침이다. 그때 끝을 다음 시작으로 낮추면 겹침이 사라져 적재 애그리거트가 볼 것이 없어지고, 겹친 타임라인이 조용히 저장된다. 메우기는 <b>늘리는
     * 것만</b> 해야 한다.
     */
    @Test
    void neverShrinksASectionSoRealOverlapStillReachesTheAggregate() {
        Fixture fixture = fixture();
        String overlapping = section("앞 구간", 0, 600_000) + "," + section("뒤 구간", 300_000, 900_000);
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse(sections(overlapping)), MediaType.APPLICATION_JSON));

        ContentAnalysisOutcome outcome = fixture.adapter().analyze(request());

        assertThat(outcome.value()).isPresent();
        // 앞 구간의 끝이 그대로 남아야 겹침이 살아 있다. 300_000 으로 줄면 겹침이 지워진 것이다.
        assertThat(outcome.analysis().sections().getFirst().endOffsetMs()).isEqualTo(600_000);
        assertThat(outcome.analysis().sections().getLast().startOffsetMs()).isEqualTo(300_000);
    }

    /** 수업 길이를 넘는 구간은 재생할 수 없는 지점을 가리킨다. 저장 전에 버린다. */
    @Test
    void rejectsASectionThatEndsAfterTheClass() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse(sections(section("상태 관리", 0, CLASS_DURATION_MS + 1))),
                        MediaType.APPLICATION_JSON));

        ContentAnalysisOutcome outcome = fixture.adapter().analyze(request());

        assertThat(outcome.value()).isEmpty();
        assertThat(outcome.failure()).isEqualTo(ContentAnalysisFailure.UNUSABLE_RESPONSE);
    }

    /** 길이가 0 인 구간은 시크할 지점이 없다. */
    @Test
    void rejectsASectionWhoseEndIsNotAfterItsStart() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse(sections(section("상태 관리", 600_000, 600_000))), MediaType.APPLICATION_JSON));

        ContentAnalysisOutcome outcome = fixture.adapter().analyze(request());

        assertThat(outcome.value()).isEmpty();
        assertThat(outcome.failure()).isEqualTo(ContentAnalysisFailure.UNUSABLE_RESPONSE);
    }

    /** 구간이 하나도 없는 응답은 타임라인을 만들지 못한다. */
    @Test
    void rejectsAResponseWithoutSections() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse("{\"classSummary\":\"요약\",\"sections\":[]}"), MediaType.APPLICATION_JSON));

        ContentAnalysisOutcome outcome = fixture.adapter().analyze(request());

        assertThat(outcome.value()).isEmpty();
        assertThat(outcome.failure()).isEqualTo(ContentAnalysisFailure.UNUSABLE_RESPONSE);
    }

    /** 제목이 컬럼 길이를 넘으면 적재 단계에서 잘린다. 어댑터에서 걸러 실패 사유를 분명히 남긴다. */
    @Test
    void rejectsASectionTitleLongerThanTheColumn() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse(sections(section("가".repeat(201), 0, 600_000))), MediaType.APPLICATION_JSON));

        ContentAnalysisOutcome outcome = fixture.adapter().analyze(request());

        assertThat(outcome.value()).isEmpty();
        assertThat(outcome.failure()).isEqualTo(ContentAnalysisFailure.UNUSABLE_RESPONSE);
    }

    /**
     * 응답이 잘리면 모델 본문 JSON 이 깨진다. 그때 사유는 파싱 실패가 아니라 {@code finish_reason} 이어야 한다 — 상한을 올려야 풀리는 문제라 재시도 대상이 아니다.
     *
     * <p><b>봉투는 정상 JSON 이다.</b> {@code content} 값이 잘린 조각을 흉내 내는 {@code "{"} 한 글자라, 세어 보면 중괄호가 하나 빠진 것처럼 보인다. 그것은 문자열 <b>안</b>의
     * 글자이고 구조적 중괄호가 아니다. 봉투가 깨져 있으면 어댑터가 파싱 예외를 잡아 {@code UNAVAILABLE} 을 돌려주므로, 아래 단정이 그 착각을 잡아 준다.
     */
    @Test
    void rejectsAnIncompleteResponse() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(CHAT_URL))
                .andRespond(MockRestResponseCreators.withSuccess("""
                        {"choices":[{"finish_reason":"length","message":{"role":"assistant","content":"{","refusal":null}}]}""", MediaType.APPLICATION_JSON));

        ContentAnalysisOutcome outcome = fixture.adapter().analyze(request());

        assertThat(outcome.value()).isEmpty();
        assertThat(outcome.failure()).isEqualTo(ContentAnalysisFailure.UNUSABLE_RESPONSE);
    }

    /**
     * 예산을 넘는 전사는 고른 간격으로 줄여 보낸다.
     *
     * <p>뒤쪽을 잘라 내면 수업 후반이 구간에서 통째로 사라진다. 간격을 유지하면 구간 경계의 해상도만 낮아진다. 3시간 수업 전사(약 180KB)가 게이트웨이 본문 상한을 넘는 것이 실측 제약이다(GMS
     * 가이드 §4.1).
     */
    @Test
    void samplesTheTranscriptEvenlyWhenItExceedsTheRequestBudget() {
        Fixture fixture = fixture();
        List<ContentAnalysisLine> lines = new ArrayList<>();
        // 한 줄 약 300바이트 × 600줄 = 약 180KB. 예산(92,160B)의 두 배쯤이다.
        String text = "가".repeat(100);
        for (int index = 0; index < 600; index++) {
            lines.add(new ContentAnalysisLine("instructor", index * 3_000L, index * 3_000L + 2_000L, text));
        }

        fixture.server()
                .expect(requestTo(CHAT_URL))
                // 전사는 user 메시지 안에 JSON 문자열로 들어가 한 번 더 escape 된다.
                .andExpect(content().string(containsString("\\\"startOffsetMs\\\":0")))
                .andExpect(content().string(withinGatewayLimit()))
                .andRespond(MockRestResponseCreators.withSuccess(
                        chatResponse(sections(section("상태 관리", 0, 600_000))), MediaType.APPLICATION_JSON));

        ContentAnalysisOutcome outcome =
                fixture.adapter().analyze(new ContentAnalysisRequest(CLASS_DURATION_MS, List.copyOf(lines)));

        assertThat(outcome.value()).isPresent();
        fixture.server().verify();
    }

    /** 게이트웨이 실측 상한(102,400B)을 넘지 않는지. 넘으면 본문이 잘려 조용히 실패한다(GMS 가이드 §4.1). */
    private static Matcher<String> withinGatewayLimit() {
        return new TypeSafeMatcher<>() {

            @Override
            protected boolean matchesSafely(String body) {
                return body.getBytes(StandardCharsets.UTF_8).length < 102_400;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("a request body smaller than the 102,400B gateway limit");
            }
        };
    }
}
