package com.a105.zani.postclass.infrastructure.gms;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import com.a105.zani.common.infrastructure.gms.GmsProperties;
import com.a105.zani.postclass.application.port.AnalyzedSection;
import com.a105.zani.postclass.application.port.ContentAnalysis;
import com.a105.zani.postclass.application.port.ContentAnalysisLine;
import com.a105.zani.postclass.application.port.ContentAnalysisOutcome;
import com.a105.zani.postclass.application.port.ContentAnalysisRequest;
import com.a105.zani.report.domain.model.SessionReport;
import com.a105.zani.report.domain.model.SessionSection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 GMS 게이트웨이에 한 번 호출해 계약을 확인한다(S15P11A105-248).
 *
 * <p><b>기본적으로 실행되지 않는다.</b> 크레딧을 쓰는 호출이라 두 가지를 모두 갖춰야 켜진다 — 옵트인 변수 {@code ZANI_GMS_LIVE_CHECK=true} 와
 * {@code GMS_API_KEY}. CI 가 키를 갖고 있어도 옵트인 변수가 없으면 건너뛴다.
 *
 * <pre>
 * ZANI_GMS_LIVE_CHECK=true GMS_API_KEY=... ./gradlew test --tests '*GmsContentAnalysisLiveCheckTest*' -i
 * </pre>
 *
 * <p>모의 전사를 넣어 <b>모의 응답으로는 확인할 수 없는 것</b>을 본다: 게이트웨이가 이 모델에서 {@code strict} json_schema 를 실제로 강제하는지(GMS 가이드 §11 이 미확인으로
 * 남겨 둔 항목), {@code max_completion_tokens} 가 받아들여지는지, 그리고 돌아온 구간이 적재 애그리거트의 불변식(겹침 없음·시간순·수업 길이 안)을 그대로 통과하는지다.
 *
 * <p>모델은 {@code GMS_ANALYSIS_MODEL} 로 바꿀 수 있고 기본값은 {@code gpt-4o-mini} 다 — 실측 확인용으로 가장 값싼 multimodal 모델이다.
 */
@EnabledIfEnvironmentVariable(named = "ZANI_GMS_LIVE_CHECK", matches = "true")
class GmsContentAnalysisLiveCheckTest {

    private static final String DEFAULT_BASE_URL = "https://gms.ssafy.io/gmsapi/api.openai.com";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";

    /** 45분 수업. 모의 전사의 오프셋이 이 안에 들어 있어야 구간 검증이 의미를 갖는다. */
    private static final long CLASS_DURATION_MS = 45 * 60 * 1_000L;

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 주제가 세 번 바뀌는 모의 전사. 구간이 하나로 뭉치는지 나뉘는지 눈으로 확인할 수 있게 만든다. */
    private static List<ContentAnalysisLine> mockTranscript() {
        return List.of(
                new ContentAnalysisLine(2_000, 32_000, "자, 오늘은 React의 상태 관리를 깊이 있게 다뤄보겠습니다."),
                new ContentAnalysisLine(195_000, 226_000, "useState는 지역 상태에 적합하지만 전역 상태는 다른 접근이 필요해요."),
                new ContentAnalysisLine(400_000, 431_000, "상태를 여러 단계로 내려주다 보면 props drilling 문제가 생깁니다."),
                new ContentAnalysisLine(520_000, 551_000, "먼저 Context API의 리렌더링 이슈를 이해해야 합니다."),
                new ContentAnalysisLine(730_000, 745_000, "Context랑 Redux는 어떤 기준으로 골라야 하나요?"),
                new ContentAnalysisLine(755_000, 790_000, "전역성이 크고 미들웨어가 필요하면 라이브러리, 아니면 Context가 낫습니다."),
                new ContentAnalysisLine(1_145_000, 1_180_000, "예제 코드로 리렌더가 어디서 발생하는지 확인해볼게요."),
                new ContentAnalysisLine(1_450_000, 1_490_000, "그래서 useMemo로 value를 메모이즈하는 패턴이 나옵니다."),
                new ContentAnalysisLine(1_865_000, 1_900_000, "다음으로 외부 상태 관리 라이브러리를 비교해볼게요."),
                new ContentAnalysisLine(2_530_000, 2_570_000, "이제 Zustand로 직접 스토어를 만들어 보겠습니다."),
                new ContentAnalysisLine(3_080_000, 3_120_000, "정리하면 상태의 범위를 먼저 정하고 도구를 고르는 순서입니다."));
    }

    @Test
    void realGatewayHonoursTheStructuredOutputContract() {
        String baseUrl = env("GMS_BASE_URL", DEFAULT_BASE_URL);
        String apiKey = System.getenv("GMS_API_KEY");
        assertNotNull(apiKey, "GMS_API_KEY 가 필요합니다");
        String model = env("GMS_ANALYSIS_MODEL", DEFAULT_MODEL);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(60_000);
        ClientHttpRequestFactory requestFactory = factory;
        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();

        GmsContentAnalysisHttpAdapter adapter = new GmsContentAnalysisHttpAdapter(
                restClient,
                JsonMapper.builder().build(),
                new GmsProperties(
                        baseUrl,
                        apiKey,
                        false,
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(2),
                        "whisper-1",
                        Duration.ofSeconds(20),
                        "ko",
                        "gpt-5.4-mini",
                        Duration.ofSeconds(6),
                        model,
                        Duration.ofSeconds(60)),
                new ContentAnalysisProperties(4000));

        ContentAnalysisOutcome outcome =
                adapter.analyze(new ContentAnalysisRequest("React 상태 관리 심화", CLASS_DURATION_MS, mockTranscript()));

        System.out.println("[live] model=" + model + " failure=" + outcome.failure());
        outcome.value().ifPresent(analysis -> {
            System.out.println("[live] classSummary=" + analysis.classSummary());
            for (AnalyzedSection section : analysis.sections()) {
                System.out.printf(
                        "[live] %d~%dms | %s | %s%n",
                        section.startOffsetMs(), section.endOffsetMs(), section.title(), section.summary());
            }
        });

        assertTrue(outcome.value().isPresent(), "실제 게이트웨이가 쓸 수 있는 응답을 주지 못했습니다: " + outcome.failure());
        ContentAnalysis analysis = outcome.analysis();
        assertFalse(analysis.sections().isEmpty());

        // 적재 애그리거트가 받아들이는지까지 본다 — 어댑터 검증만 통과하고 저장 단계에서 거절되면 그 세션은 리포트를 받지 못한다.
        SessionReport.create(
                1L,
                analysis.classSummary(),
                analysis.sections().stream()
                        .map(section -> SessionSection.of(
                                section.title(), section.summary(), section.startOffsetMs(), section.endOffsetMs()))
                        .toList(),
                CLASS_DURATION_MS);
    }
}
