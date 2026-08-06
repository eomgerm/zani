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
import com.a105.zani.postclass.application.port.InstructorAnalysis;
import com.a105.zani.postclass.application.port.InstructorAnalysisPort;
import com.a105.zani.postclass.application.port.InstructorAnalysisRequest;
import com.a105.zani.postclass.infrastructure.config.InstructorAnalysisProperties;

/**
 * GMS chat completions 로 세션 하나분 강사 분석을 받아 온다. (S15P11A105-250)
 *
 * <p>{@code max_tokens} 는 400 으로 거부되므로 {@code max_completion_tokens} 를 쓴다. {@code strict} 스키마에
 * {@code additionalProperties: false} 를 짝지어야 모델이 스키마를 벗어나지 못한다.
 *
 * <p>{@code evidenceKinds} 의 {@code minItems: 2} 가 AI-008(근거 두 종류 이상 결합)을 구조로 강제한다. 다만 GMS 가 배열 하한을 실제로 강제하는지는 확인하지
 * 못했으므로 {@code AnalyzeSessionInstructorService} 가 한 번 더 걸러낸다 — {@code GmsTipConceptHttpAdapter} 가 {@code minimum} 에 같은
 * 판단을 한다.
 *
 * <p>재시도하지 않는다. 형제 어댑터 {@code GmsTipConceptHttpAdapter} 와 같고, rate limit 은 ZANI 규모에서 실질적 제약이 아니다.
 *
 * <p>본문에는 구간 번호와 익명 집계만 들어간다. 요청 타입 자체가 식별자를 담지 않으므로 여기서 걸러 낼 것이 없다.
 */
@Component
@ConditionalOnProperty(prefix = "gms", name = "mock-enabled", havingValue = "false")
public class GmsInstructorAnalysisHttpAdapter implements InstructorAnalysisPort {

    private static final Logger log = LoggerFactory.getLogger(GmsInstructorAnalysisHttpAdapter.class);
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private static final int FEEDBACK_MAX_LENGTH = 2_000;
    private static final int TITLE_MAX_LENGTH = 200;
    private static final int MAX_INSIGHTS = 4;
    private static final int MIN_SCORE = 0;
    private static final int MAX_SCORE = 100;

    /** 필드가 누락된 인사이트의 구간 번호. 0 은 "전체 수업" 이라는 뜻이 있어 누락을 0 으로 접으면 근거 없는 인사이트가 전체 수업 대상으로 저장된다. 서비스의 범위 검사가 이 값을 제거한다. */
    private static final int MISSING_SECTION_INDEX = -1;

    /**
     * 추출 지시. 골격과 공통 블록은 {@link AnalysisPrompts} 를 따른다 — 역할·역할 경계·금지·말투 다음에 응답 필드마다 한 블록이다.
     *
     * <p>학생 개인 지목 금지를 명시한다(REPORT-I-002). 입력에 식별자가 없어도 "카메라를 끈 학생이 있었다" 같은 문장은 나올 수 있고, 강사가 그대로 읽는 문구다.
     *
     * <p>강사 평가 금지를 명시한다(FRD §17.4). 스키마에 감정·성격·역량 필드가 없어도 종합 피드백 문장에는 들어갈 수 있다.
     *
     * <p><b>인사이트에 "유지" 유형을 함께 둔다.</b> 개선 행동만 인사이트로 인정하면 잘 굴러간 짧은 수업에서 모델이 지시대로 0개를 내고, 그때 화면에는 "수업 인사이트가 아직 없어요" 만 남아
     * 분야별 평가 옆 칸이 빈다. "이 부분이 좋았으니 이어가세요" 도 강사가 읽을 값이므로 자리를 만들어 준다.
     *
     * <p><b>개수 하한을 두지 않는다.</b> 목표 개수와 품질 제약을 함께 주면 모델은 목표를 먼저 맞추고 근거를 사후에 만들어낸다. 그리고
     * {@code AnalyzeSessionInstructorService} 의 근거 검사는 {@code evidenceKinds} 에 <b>유효한 종류가 둘 적혀 있는지</b>만 보므로 그 조작을 잡지
     * 못한다 — 서버가 걸러 줄 수 없으니 프롬프트가 자제를 유지해야 한다. 개수를 늘리는 압력은 하한이 아니라 "유지" 유형으로 재료를 늘려 만든다.
     */
    private static final String SYSTEM_PROMPT = """
            너는 수업 하나의 익명 집단 기록과 강사 메모를 읽고, 강사에게 줄 종합 피드백과 수업 품질 평가와
            수업 인사이트를 만든다.

            %s

            [금지]
            - 학생 개인을 지목하지 않는다. 이름, 별칭, "한 학생", 인원 목록을 쓰지 않는다. 집단 수치로만 말한다.
            - 강사의 감정, 성격, 성실성, 역량을 평가하지 않는다. 점수는 수업 품질에 대한 것이고 사람에 대한
              것이 아니다.
            - 수업 전체를 하나의 점수, 등급, 한 줄 평으로 요약하지 않는다. 네 분야 점수를 합치거나 평균 내지
              않는다.
            - 데이터의 영문 코드값(CONFUSED, MISSED, NO_RESPONSE, OK, UNMEASURABLE, CAMERA_OFF,
              TIP_DELIVERED, TIP_UNAVAILABLE)을 문장에 그대로 쓰지 않는다. 강사가 읽어 뜻이 통하는
              한국어로 풀어 쓴다.

            %s

            [questionCount]
            - publicChats 중 질문인 발화만 센 수다. 채팅 수가 아니다 — "네", "감사합니다", "잘 들려요"
              같은 반응은 세지 않는다.
            - 물음표가 없어도 묻는 문장이면 세고, "다시 설명해주실 수 있나요" 처럼 완곡한 요청도 센다.
            - 질문이 없으면 0 이다.

            [overallFeedback]
            - 관측된 사실만 담고, 순서대로 쓴다.
              먼저 이번 수업에서 잘 작동한 것 → 그다음 아쉬운 것 → 마지막에 다음 수업에서 할 것 하나.
            - 인정은 관측된 진행에 붙인다.
              쓴다: "확인 필요 비율이 올라간 뒤 설명을 다시 짚어 흐름을 되돌렸어요."
              쓰지 않는다: "열정적으로 수업하셨어요." — 사람에 대한 평가다.
            - 잘 작동한 관측이 없으면 그 문장을 빼고 아쉬운 것부터 쓴다. 없는 칭찬을 지어내지 않는다.
            - 관측이 없으면 측정된 기록이 없다는 사실을 쓴다. 추측으로 채우지 않는다.
            - 세 대목을 각각 한두 문장씩, 전체 5~8문장으로 쓴다. 집계 숫자만 옮겨 한 줄로 끝내지 않는다.
            - 관측을 짚을 때는 수업의 어느 대목에서 무엇이 있었는지까지 쓴다.

            [scores]
            - 네 분야를 0~100 으로 각각 매긴다. 수업 품질 유형별 평가다.
              delivery          전달력 — 설명의 명확성과 전달 방식
              structureFlow     구성·흐름 — 개념 순서와 전환, 흐름 회복
              interaction       상호작용 — 질문 응답, 확인 질문, 참여 유도
              difficultyControl 난이도 조절 — 속도와 정보량이 학습자에 맞았는가
            - 근거가 약한 분야는 중간값 부근에 둔다. 근거 없이 극단값을 주지 않는다.

            [insights]
            - 근거가 있는 만큼 쓴다. 최대 4개이며 보통 2~4개가 나온다. 근거가 없는 항목은 넣지 않는다.
            - 인사이트는 두 종류다. 근거가 있으면 어느 쪽이든 놓치지 않는다.
              개선  다음 수업에서 바꾸면 나아질 지점
              유지  이번 수업에서 잘 작동한 지점. 강사가 그대로 이어가면 좋을 것
            - 개선할 지점이 마땅치 않은 짧은 수업이면 유지 인사이트로 채운다. 없는 문제를 지어내
              개선 인사이트를 만들지 않는다.
            - 각 인사이트는 근거를 두 종류 이상 결합해야 한다. evidenceKinds 에 실제로 쓴 종류를 전부
              넣는다. 한 종류만 짚을 수 있는 관찰이면 인사이트로 만들지 않는다.
              ATTENTION_FLOW  집단 알림의 확인 필요 비율 흐름
              CHECK_RESPONSE  이해 확인 응답 분포
              CHAT            공개 채팅과 질문
              SECTION_SUMMARY 구간 제목과 요약
              INTERACTION     손들기 등 공개 상호작용
              INSTRUCTOR_NOTE 강사가 확정한 메모
              COACHING_TIP    수업 중 전달된 실시간 팁 이력
            - title 은 강사가 목록에서 훑어 알 수 있는 짧은 명사구로 쓴다.
            - evidence 는 무엇을 관측했는지 두세 문장으로 쓴다. 결합한 근거 종류가 각각 무엇을 보여
              주었는지 함께 쓴다. 수치를 쓸 때는 집단 수치만 쓴다.
            - suggestion 은 다음 수업에서 할 구체적 행동 하나를 쓴다. 유지 인사이트라서 덧붙일 행동이
              없으면 빈 문자열로 둔다. 억지로 바꿀 것을 만들지 않는다.
            - sectionIndex 는 sections 에 있는 번호만 쓴다. 특정 구간이 아니라 수업 전체에 대한 관찰이면
              0 을 쓴다. 같은 구간을 두 번 짚지 않는다.
            - 인사이트는 서로 다른 내용이어야 한다. 같은 지적을 sectionIndex 만 바꿔 반복하지 않는다.""".formatted(
                    AnalysisPrompts.roleBoundary(
                            "lectureTitle, classSummary, sections, groupAlerts, deliveredTips, publicChats,"
                                    + " instructorNote"),
                    AnalysisPrompts.tone("강사"));
    ;

    private static final Map<String, Object> RESPONSE_FORMAT = responseFormat();

    private final RestClient analysisRestClient;
    private final ObjectMapper objectMapper;
    private final String analysisModel;
    private final int maxCompletionTokens;

    public GmsInstructorAnalysisHttpAdapter(
            @Qualifier("gmsAnalysisRestClient") RestClient analysisRestClient,
            ObjectMapper objectMapper,
            GmsProperties gmsProperties,
            InstructorAnalysisProperties analysisProperties) {
        this.analysisRestClient = analysisRestClient;
        this.objectMapper = objectMapper;
        this.analysisModel = gmsProperties.analysisModel();
        this.maxCompletionTokens = analysisProperties.maxCompletionTokens();
    }

    @Override
    public Optional<InstructorAnalysis> analyze(InstructorAnalysisRequest request) {
        long startedAt = System.nanoTime();
        try {
            ChatResponse response = analysisRestClient
                    .post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(request))
                    .retrieve()
                    .body(ChatResponse.class);
            return parse(response, elapsedMs(startedAt));
        } catch (RuntimeException exception) {
            // timeout, 401(자격증명), 402(크레딧 소진), 429(rate limit), 5xx 를 모두 같은 실패로 다룬다.
            log.warn("Instructor analysis failed after {}ms: {}", elapsedMs(startedAt), exception.toString());
            return Optional.empty();
        }
    }

    private Map<String, Object> requestBody(InstructorAnalysisRequest request) {
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

    /** 데이터를 JSON 으로 감싼다. 평문으로 이어 붙이면 채팅과 메모 문장이 지시처럼 보인다. */
    private String userPrompt(InstructorAnalysisRequest request) {
        return objectMapper.writeValueAsString(request.dataPayload());
    }

    private static Map<String, Object> responseFormat() {
        Map<String, Object> score = Map.of("type", "integer", "minimum", MIN_SCORE, "maximum", MAX_SCORE);
        Map<String, Object> scores = object(
                Map.of(
                        "delivery", score,
                        "structureFlow", score,
                        "interaction", score,
                        "difficultyControl", score),
                List.of("delivery", "structureFlow", "interaction", "difficultyControl"));
        Map<String, Object> insight = object(
                Map.of(
                        "sectionIndex",
                        Map.of("type", "integer", "minimum", 0),
                        "title",
                        Map.of("type", "string", "maxLength", TITLE_MAX_LENGTH),
                        "evidence",
                        Map.of("type", "string"),
                        "suggestion",
                        Map.of("type", "string"),
                        "evidenceKinds",
                        array(
                                Map.of("type", "string", "enum", InstructorAnalysis.EVIDENCE_KINDS),
                                InstructorAnalysis.MIN_EVIDENCE_KINDS,
                                InstructorAnalysis.EVIDENCE_KINDS.size())),
                List.of("sectionIndex", "title", "evidence", "suggestion", "evidenceKinds"));
        Map<String, Object> analysis = object(
                Map.of(
                        "questionCount",
                        Map.of("type", "integer", "minimum", 0),
                        "overallFeedback",
                        Map.of("type", "string", "maxLength", FEEDBACK_MAX_LENGTH),
                        "scores",
                        scores,
                        "insights",
                        array(insight, 0, MAX_INSIGHTS)),
                List.of("questionCount", "overallFeedback", "scores", "insights"));
        return Map.of(
                "type",
                "json_schema",
                "json_schema",
                Map.of("name", "instructor_analysis", "strict", true, "schema", analysis));
    }

    /** strict 스키마는 object 마다 required 전량과 additionalProperties: false 를 요구한다. */
    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }

    private static Map<String, Object> array(Map<String, Object> items, int minItems, int maxItems) {
        return Map.of("type", "array", "items", items, "minItems", minItems, "maxItems", maxItems);
    }

    private Optional<InstructorAnalysis> parse(ChatResponse response, long elapsedMs) {
        Choice choice = response == null
                        || response.choices() == null
                        || response.choices().isEmpty()
                ? null
                : response.choices().getFirst();
        if (choice == null || choice.message() == null) {
            log.warn("Instructor analysis empty choices after {}ms", elapsedMs);
            return Optional.empty();
        }
        if (choice.message().refusal() != null) {
            log.warn("Instructor analysis refused after {}ms", elapsedMs);
            return Optional.empty();
        }
        if (!"stop".equals(choice.finishReason())) {
            // length 면 JSON 이 잘려 파싱도 실패한다. 사유를 남겨 max_completion_tokens 를 의심할 수 있게 한다.
            log.warn("Instructor analysis incomplete after {}ms: finishReason={}", elapsedMs, choice.finishReason());
            return Optional.empty();
        }

        AnalysisResponse parsed;
        try {
            parsed = objectMapper.readValue(choice.message().content(), AnalysisResponse.class);
        } catch (Exception exception) {
            // strict 스키마를 썼어도 모델이 스키마 밖 응답을 낼 여지를 남긴다.
            log.warn("Instructor analysis schema invalid after {}ms: {}", elapsedMs, exception.toString());
            return Optional.empty();
        }
        if (parsed.overallFeedback() == null
                || parsed.overallFeedback().isBlank()
                || parsed.scores() == null
                || parsed.scores().hasMissingField()) {
            // 종합 피드백이나 점수가 없으면 저장할 것이 없다. 근거 검증(항목 제거)과 달리 이건 스키마 위반이다.
            log.warn("Instructor analysis missing required fields after {}ms", elapsedMs);
            return Optional.empty();
        }
        log.info("Instructor analysis received: elapsedMs={}", elapsedMs);
        return Optional.of(toAnalysis(parsed));
    }

    private InstructorAnalysis toAnalysis(AnalysisResponse parsed) {
        List<InsightResponse> given = parsed.insights() == null ? List.of() : parsed.insights();
        List<InstructorAnalysis.InsightDraft> insights = given.stream()
                .map(insight -> new InstructorAnalysis.InsightDraft(
                        insight.sectionIndex() == null ? MISSING_SECTION_INDEX : insight.sectionIndex(),
                        insight.title(),
                        insight.evidence(),
                        insight.suggestion(),
                        insight.evidenceKinds() == null ? List.of() : insight.evidenceKinds()))
                .toList();
        return new InstructorAnalysis(
                // 음수는 스키마가 막지만 강제 여부가 미확인이라 서버에서 한 번 더 접는다.
                parsed.questionCount() == null ? 0 : Math.max(0, parsed.questionCount()),
                parsed.overallFeedback(),
                new InstructorAnalysis.Scores(
                        parsed.scores().delivery(),
                        parsed.scores().structureFlow(),
                        parsed.scores().interaction(),
                        parsed.scores().difficultyControl()),
                insights);
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    /** GMS(OpenAI 호환) chat completions 응답. 필요한 필드만 받는다. */
    private record ChatResponse(List<Choice> choices) {}

    private record Choice(
            Message message, @JsonProperty("finish_reason") String finishReason) {}

    private record Message(String content, String refusal) {}

    /** 모델이 스키마대로 낸 본문. 검증 전 값이라 포트 타입과 분리한다. 박싱 타입으로 두어 필드 누락과 0 을 구분한다. */
    private record AnalysisResponse(
            Integer questionCount, String overallFeedback, ScoresResponse scores, List<InsightResponse> insights) {}

    private record ScoresResponse(
            Integer delivery, Integer structureFlow, Integer interaction, Integer difficultyControl) {

        boolean hasMissingField() {
            return delivery == null || structureFlow == null || interaction == null || difficultyControl == null;
        }
    }

    private record InsightResponse(
            Integer sectionIndex, String title, String evidence, String suggestion, List<String> evidenceKinds) {}
}
