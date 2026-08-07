package com.a105.zani.postclass.infrastructure.gms;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import com.a105.zani.common.infrastructure.gms.GmsProperties;
import com.a105.zani.postclass.application.port.StudentAnalysis;
import com.a105.zani.postclass.application.port.StudentAnalysisPort;
import com.a105.zani.postclass.application.port.StudentAnalysisRequest;
import com.a105.zani.postclass.infrastructure.config.StudentAnalysisProperties;

/**
 * GMS chat completions 로 학생 한 명분 분석을 받아 온다. (S15P11A105-249)
 *
 * <p>{@code max_tokens} 는 400 으로 거부되므로 {@code max_completion_tokens} 를 쓴다. {@code strict} 스키마에
 * {@code additionalProperties: false} 를 짝지어야 모델이 스키마를 벗어나지 못한다(GMS 가이드 §6).
 *
 * <p>재시도하지 않는다. 형제 어댑터 {@code GmsTipConceptHttpAdapter} 와 같고, rate limit 은 ZANI 규모에서 실질적 제약이 아니다(가이드 §5).
 *
 * <p>본문에는 별칭과 구간 번호만 들어간다. 요청 타입 자체가 식별자를 담지 않으므로 여기서 걸러 낼 것이 없다.
 */
@Component
@ConditionalOnProperty(prefix = "gms", name = "mock-enabled", havingValue = "false")
public class GmsStudentAnalysisHttpAdapter implements StudentAnalysisPort {

    private static final Logger log = LoggerFactory.getLogger(GmsStudentAnalysisHttpAdapter.class);
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private static final int SUMMARY_MAX_LENGTH = 2_000;
    private static final int TITLE_MAX_LENGTH = 200;
    private static final int MAX_RECOMMENDATIONS = 5;
    private static final int MIN_QUESTIONS = 3;
    private static final int MAX_QUESTIONS = 5;
    private static final int REQUIRED_OPTIONS = 4;

    /**
     * 추출 지시. 골격과 공통 블록은 {@link AnalysisPrompts} 를 따른다 — 역할·역할 경계·금지·말투 다음에 응답 필드마다 한 블록이다.
     *
     * <p>평가 금지를 명시한다(FRD §17.4). 스키마에 감정·성격·역량 필드가 없어도 요약 문장에는 들어갈 수 있고, 그 문장은 학생이 그대로 읽는다.
     *
     * <p><b>{@code participationSummary} 에 순서를 준다.</b> 상한이 2,000자인데 구조 지시가 없으면 모델은 빈 칸을 관측 나열로 채운다 — 인정할 관측이 있어도 넣을 자리가
     * 없어서 빠진다. 순서를 정하면 칭찬이 "지시" 가 아니라 "자리" 가 되고, {@link AnalysisPrompts#tone} 의 사실 결속 규칙이 그 자리를 평가로 넘어가지 않게 막는다.
     */
    private static final String SYSTEM_PROMPT = """
            너는 수업 하나에서 학생 한 명의 참여 기록을 읽고, 그 학생에게 줄 참여도 요약과 복습 구간 추천과 복습 퀴즈를 만든다.

            %s

            [금지]
            - 성격, 태도, 성실성, 감정, 역량을 평가하지 않는다. 관측된 행동만 쓴다.
            - 관측이 없으면 측정된 기록이 없다는 사실을 쓴다. 추측으로 채우지 않는다.
            - observations 의 영문 코드값(CONFUSED, MISSED, NO_RESPONSE, OK, NOT_ENGAGED, BARELY_ENGAGED,
              ENGAGED, HIGHLY_ENGAGED, UNMEASURABLE, CAMERA_OFF)을 문장에 그대로 쓰지 않는다. 학생이 읽어
              뜻이 통하는 한국어로 풀어 쓴다.

            %s

            [participationSummary]
            - 관측된 사실만 담고, 순서대로 쓴다.
              먼저 인정할 관측 → 그다음 아쉬운 관측 → 마지막에 다음 수업에서 해 볼 것 하나.
            - 인정은 관측된 행동에 붙인다.
              쓴다: "3번 구간 확인 질문에 바로 답했어요."
              쓰지 않는다: "성실하게 참여하셨네요." — 사람에 대한 평가다.
            - 인정할 관측이 없으면 그 문장을 빼고 아쉬운 관측부터 쓴다. 없는 칭찬을 지어내지 않는다.
            - 아쉬운 관측이 여러 개면 학생이 다음에 손댈 수 있는 것부터 쓴다.
            - 세 대목을 각각 한두 문장씩, 전체 5~8문장으로 쓴다. 관측을 한 줄로 요약해 끝내지 않는다.
            - 관측을 짚을 때는 어느 구간에서 무엇이 있었는지까지 쓴다. 집계 숫자만 옮기지 않는다.

            [questionCount]
            - observations 의 채팅 중 질문인 발화만 센 수다. 채팅 수가 아니다 —
              "네", "감사합니다", "잘 들려요" 같은 반응은 세지 않는다.
            - 물음표가 없어도 묻는 문장이면 세고, "다시 설명해주실 수 있나요" 처럼 완곡한 요청도 센다.
            - 질문이 없으면 0 이다.

            [recommendations]
            - 0개부터 5개까지다. 근거가 없으면 넣지 않는다.
            - observations 에 단서가 한 건뿐인 구간은 추천하지 않는다. 같은 구간에 근거가 겹칠 때만 넣는다.
            - sectionIndex 는 sections 에 있는 번호만 쓴다. 같은 구간을 두 번 넣지 않는다.
            - 서로 다른 내용이어야 한다. 같은 제목이나 같은 설명을 sectionIndex 만 바꿔 반복하지 않는다.
              5개를 채우려 하지 말고 근거가 있는 만큼만 넣는다.
            - type 은 sectionSignals 에서 그 구간의 집계만 보고 정한다. 시각을 직접 구간 경계와 비교하지 않는다 —
              sectionSignals 는 서버가 이미 구간별로 센 값이고, sectionIndex 로 짝이 맞는다.
              confusedCount 가 있으면 CONFUSED, missedCount 는 MISSED, noResponseCount 는 NO_RESPONSE,
              chatCount 는 QUESTION, lowEngagementCount 가 두드러지면 LOW_ENGAGEMENT 다.
            - 학생이 그 개념을 물었더라도 그 구간의 chatCount 가 0 이면 이 구간의 근거는 질문이 아니다.
            - type 은 그 구간의 집계 중 가장 큰 근거 하나를 고른다. 다섯 가지뿐이다.
              CONFUSED       확인 프롬프트에 "헷갈려요" 로 응답한 구간
              MISSED         확인 프롬프트에 "놓쳤어요" 로 응답한 구간
              NO_RESPONSE    확인 프롬프트에 응답하지 않은 구간
              LOW_ENGAGEMENT 참여도 판정이 낮게 이어진 구간
              QUESTION       학생이 질문을 남긴 구간
            - 같은 type 은 최대 두 개까지만 넣는다. 세 번째부터는 아직 나오지 않은 유형의 근거가 있는 구간을
              넣고, 그런 구간이 없으면 추천 개수를 줄인다. 5개를 채우는 것보다 유형이 고르게 보이는 것이 낫다.
            - 없는 근거를 만들어 유형을 채우지는 않는다. 상한은 구간을 고르는 순서를 정할 뿐이고, 붙이는
              유형은 그 구간의 관측이 정한다.
            - description 은 왜 이 구간을 다시 보면 좋은지 학생에게 한두 문장으로 쓴다. 관측을 근거로 든다.

            [quiz]
            - questions 는 3개부터 5개까지다. 문항마다 보기 4개이고 정답은 정확히 1개다.
            - 퀴즈는 sections 의 내용에서만 낸다. 수업에 없던 내용을 묻지 않는다.
            - 문항마다 sectionIndex 에 그 문항의 근거가 된 구간 번호를 넣는다. sections 에 있는 번호만 쓴다.
              학생이 그 구간을 다시 보게 만드는 링크가 이 값으로 만들어진다.
            - explanation 은 정답이 왜 정답인지 학생이 읽어 알 수 있게 쓴다. 정답 보기를 그대로 옮기지 않는다.""".formatted(
                    AnalysisPrompts.roleBoundary("lectureTitle, classSummary, sections, observations"),
                    AnalysisPrompts.tone("학생"));

    private static final Map<String, Object> RESPONSE_FORMAT = responseFormat();

    private final RestClient analysisRestClient;
    private final ObjectMapper objectMapper;
    private final String analysisModel;
    private final int maxCompletionTokens;

    public GmsStudentAnalysisHttpAdapter(
            @Qualifier("gmsAnalysisRestClient") RestClient analysisRestClient,
            ObjectMapper objectMapper,
            GmsProperties gmsProperties,
            StudentAnalysisProperties analysisProperties) {
        this.analysisRestClient = analysisRestClient;
        this.objectMapper = objectMapper;
        this.analysisModel = gmsProperties.analysisModel();
        this.maxCompletionTokens = analysisProperties.maxCompletionTokens();
    }

    @Override
    public Optional<StudentAnalysis> analyze(StudentAnalysisRequest request) {
        long startedAt = System.nanoTime();
        try {
            ChatResponse response = analysisRestClient
                    .post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(request))
                    .retrieve()
                    .body(ChatResponse.class);
            return parse(response, request.studentAlias(), elapsedMs(startedAt));
        } catch (RuntimeException exception) {
            // timeout, 401(자격증명), 402(크레딧 소진), 429(rate limit), 5xx 를 모두 같은 실패로 다룬다.
            log.warn(
                    "Student analysis failed for {} after {}ms: {}",
                    request.studentAlias(),
                    elapsedMs(startedAt),
                    exception.toString());
            return Optional.empty();
        }
    }

    private Map<String, Object> requestBody(StudentAnalysisRequest request) {
        return Map.of(
                "model",
                analysisModel,
                "temperature",
                0,
                "max_completion_tokens",
                maxCompletionTokens,
                "response_format",
                RESPONSE_FORMAT,
                "messages",
                List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", userPrompt(request))));
    }

    /**
     * 데이터를 JSON 으로 감싼다. 평문으로 이어 붙이면 채팅 문장이 지시처럼 보인다.
     *
     * <p>{@code sectionSignals} 는 서버가 관측을 구간별로 집계한 값이고 항상 실린다. {@code observations} 는 길이 가드가 발동하면 빠진다.
     */
    private String userPrompt(StudentAnalysisRequest request) {
        return objectMapper.writeValueAsString(request.promptPayload());
    }

    private static Map<String, Object> responseFormat() {
        Map<String, Object> option = object(
                Map.of("optionText", Map.of("type", "string"), "correct", Map.of("type", "boolean")),
                List.of("optionText", "correct"));
        Map<String, Object> question = object(
                Map.of(
                        "sectionIndex",
                        Map.of("type", "integer", "minimum", 1),
                        "questionText",
                        Map.of("type", "string"),
                        "explanation",
                        Map.of("type", "string"),
                        "options",
                        array(option, REQUIRED_OPTIONS, REQUIRED_OPTIONS)),
                List.of("sectionIndex", "questionText", "explanation", "options"));
        Map<String, Object> quiz = object(
                Map.of(
                        "title",
                        Map.of("type", "string", "maxLength", TITLE_MAX_LENGTH),
                        "description",
                        Map.of("type", "string"),
                        "questions",
                        array(question, MIN_QUESTIONS, MAX_QUESTIONS)),
                List.of("title", "description", "questions"));
        Map<String, Object> recommendation = object(
                Map.of(
                        "sectionIndex",
                        Map.of("type", "integer", "minimum", 1),
                        "type",
                        Map.of("type", "string", "enum", StudentAnalysis.RECOMMENDATION_TYPES),
                        "title",
                        Map.of("type", "string", "maxLength", TITLE_MAX_LENGTH),
                        "description",
                        Map.of("type", "string")),
                List.of("sectionIndex", "type", "title", "description"));
        Map<String, Object> analysis = object(
                Map.of(
                        "participationSummary",
                        Map.of("type", "string", "maxLength", SUMMARY_MAX_LENGTH),
                        "questionCount",
                        Map.of("type", "integer", "minimum", 0),
                        "recommendations",
                        array(recommendation, 0, MAX_RECOMMENDATIONS),
                        "quiz",
                        quiz),
                List.of("participationSummary", "questionCount", "recommendations", "quiz"));
        return Map.of(
                "type",
                "json_schema",
                "json_schema",
                Map.of("name", "student_analysis", "strict", true, "schema", analysis));
    }

    /** strict 스키마는 object 마다 required 전량과 additionalProperties: false 를 요구한다. */
    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }

    private static Map<String, Object> array(Map<String, Object> items, int minItems, int maxItems) {
        return Map.of("type", "array", "items", items, "minItems", minItems, "maxItems", maxItems);
    }

    private Optional<StudentAnalysis> parse(ChatResponse response, String alias, long elapsedMs) {
        Choice choice = response == null
                        || response.choices() == null
                        || response.choices().isEmpty()
                ? null
                : response.choices().getFirst();
        if (choice == null || choice.message() == null) {
            log.warn("Student analysis empty choices for {} after {}ms", alias, elapsedMs);
            return Optional.empty();
        }
        if (choice.message().refusal() != null) {
            log.warn("Student analysis refused for {} after {}ms", alias, elapsedMs);
            return Optional.empty();
        }
        if (!"stop".equals(choice.finishReason())) {
            // length 면 JSON 이 잘려 파싱도 실패한다. 사유를 남겨 max_completion_tokens 를 의심할 수 있게 한다.
            log.warn(
                    "Student analysis incomplete for {} after {}ms: finishReason={}",
                    alias,
                    elapsedMs,
                    choice.finishReason());
            return Optional.empty();
        }

        AnalysisResponse parsed;
        try {
            parsed = objectMapper.readValue(choice.message().content(), AnalysisResponse.class);
        } catch (Exception exception) {
            // strict 스키마를 썼어도 모델이 스키마 밖 응답을 낼 여지를 남긴다.
            log.warn("Student analysis schema invalid for {} after {}ms: {}", alias, elapsedMs, exception.toString());
            return Optional.empty();
        }
        if (parsed.participationSummary() == null
                || parsed.participationSummary().isBlank()
                || parsed.quiz() == null) {
            // 요약이나 퀴즈가 없으면 저장할 것이 없다. 근거 검증(항목 제거)과 달리 이건 스키마 위반이다.
            log.warn("Student analysis missing required fields for {} after {}ms", alias, elapsedMs);
            return Optional.empty();
        }
        log.info("Student analysis received for {}: elapsedMs={}", alias, elapsedMs);
        return Optional.of(toAnalysis(parsed));
    }

    private StudentAnalysis toAnalysis(AnalysisResponse parsed) {
        List<RecommendationResponse> givenRecommendations =
                parsed.recommendations() == null ? List.of() : parsed.recommendations();
        List<StudentAnalysis.RecommendationDraft> recommendations = givenRecommendations.stream()
                .map(recommendation -> new StudentAnalysis.RecommendationDraft(
                        recommendation.sectionIndex() == null ? 0 : recommendation.sectionIndex(),
                        recommendation.type(),
                        recommendation.title(),
                        recommendation.description()))
                .toList();
        List<QuestionResponse> givenQuestions =
                parsed.quiz().questions() == null ? List.of() : parsed.quiz().questions();
        List<StudentAnalysis.QuestionDraft> questions = givenQuestions.stream()
                .map(question -> new StudentAnalysis.QuestionDraft(
                        question.sectionIndex() == null ? 0 : question.sectionIndex(),
                        question.questionText(),
                        question.explanation(),
                        options(question.options())))
                .toList();
        return new StudentAnalysis(
                parsed.participationSummary(),
                // 음수는 스키마가 막지만 강제 여부가 미확인이라(가이드 §6) 서버에서 한 번 더 접는다.
                parsed.questionCount() == null ? 0 : Math.max(0, parsed.questionCount()),
                recommendations,
                new StudentAnalysis.QuizDraft(
                        parsed.quiz().title(), parsed.quiz().description(), questions));
    }

    private List<StudentAnalysis.OptionDraft> options(List<OptionResponse> given) {
        return (given == null ? List.<OptionResponse>of() : given)
                .stream()
                        .map(option -> new StudentAnalysis.OptionDraft(
                                option.optionText(), Boolean.TRUE.equals(option.correct())))
                        .toList();
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    /** GMS(OpenAI 호환) chat completions 응답. 필요한 필드만 받는다. */
    private record ChatResponse(List<Choice> choices) {}

    private record Choice(
            Message message, @JsonProperty("finish_reason") String finishReason) {}

    private record Message(String content, String refusal) {}

    /** 모델이 스키마대로 낸 본문. 검증 전 값이라 포트 타입과 분리한다. 박싱 타입으로 두어 필드 누락과 0·false 를 구분한다. */
    private record AnalysisResponse(
            String participationSummary,
            Integer questionCount,
            List<RecommendationResponse> recommendations,
            QuizResponse quiz) {}

    private record RecommendationResponse(Integer sectionIndex, String type, String title, String description) {}

    private record QuizResponse(String title, String description, List<QuestionResponse> questions) {}

    private record QuestionResponse(
            Integer sectionIndex, String questionText, String explanation, List<OptionResponse> options) {}

    private record OptionResponse(String optionText, Boolean correct) {}
}
