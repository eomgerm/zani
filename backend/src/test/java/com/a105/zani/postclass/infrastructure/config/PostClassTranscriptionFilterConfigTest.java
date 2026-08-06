package com.a105.zani.postclass.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.a105.zani.common.infrastructure.gms.GmsProperties;
import com.a105.zani.postclass.application.port.TranscriptFilterSettings;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설정값이 조립 필터에 어떻게 닿는지, 그리고 잘못된 값이 <b>기동 시점에</b> 걸리는지 본다(S15P11A105-306).
 *
 * <p>범위 검사를 record 단위 테스트로만 두면 "바인딩을 통과해 기동한 뒤에야 터진다" 를 확인할 수 없다. 임곗값 오설정은 조용히 넘어가면 전사를 통째로 지우거나 아무것도 지우지 않는 두 방향으로
 * 틀리므로, 실제로 컨텍스트가 뜨지 않는 것까지 고정한다.
 */
class PostClassTranscriptionFilterConfigTest {

    /**
     * {@code gms.transcribe-language} 를 넣는 이유는 같은 설정 클래스의 오케스트레이션 빈이 그 값을 요구하기 때문이다(전사 언어의 정본은 GMS 요청 설정이다). 이 테스트가 보는
     * 필터 빈과는 무관하지만, 빼면 사방의 케이스가 "필터 때문이 아닌 이유로" 기동 실패해 아래 검사가 통과하는 것처럼 보인다.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FilterConfig.class)
            .withPropertyValues("gms.transcribe-language=ko");

    @Test
    void 기본값은_반복_규칙과_무음_규칙을_모두_켠다() {
        // 저장된 32개 트랙에서 실제 발화 최댓값은 0.921 이고 0.98 이상 9건은 전부 환각이었다.
        // 반복 규칙을 주 수단으로 유지하면서 0.98 규칙을 극단적인 무음 환각의 꼬리 정리로 켠다.
        runner.run(context -> {
            assertThat(context).hasSingleBean(TranscriptFilterSettings.class);
            TranscriptFilterSettings settings = context.getBean(TranscriptFilterSettings.class);
            assertThat(settings.silenceHallucinationFilterEnabled()).isTrue();
            assertThat(settings.noSpeechThreshold()).isEqualTo(0.98);
            assertThat(settings.repeatedPhraseFilterEnabled()).isTrue();
        });
    }

    @Test
    void 무음_규칙을_명시적으로_켜도_유지된다() {
        runner.withPropertyValues("postclass.transcription.hallucination-filter-enabled=true")
                .run(context -> assertThat(
                                context.getBean(TranscriptFilterSettings.class).silenceHallucinationFilterEnabled())
                        .isTrue());
    }

    @Test
    void 반복_규칙도_설정으로_끌_수_있다() {
        runner.withPropertyValues("postclass.transcription.repeated-phrase-filter-enabled=false")
                .run(context -> assertThat(
                                context.getBean(TranscriptFilterSettings.class).repeatedPhraseFilterEnabled())
                        .isFalse());
    }

    @Test
    void 설정으로_즉시_끌_수_있다() {
        runner.withPropertyValues("postclass.transcription.hallucination-filter-enabled=false")
                .run(context -> assertThat(
                                context.getBean(TranscriptFilterSettings.class).silenceHallucinationFilterEnabled())
                        .isFalse());
    }

    @Test
    void 임곗값을_설정으로_바꿀_수_있다() {
        runner.withPropertyValues("postclass.transcription.no-speech-threshold=0.9")
                .run(context -> assertThat(
                                context.getBean(TranscriptFilterSettings.class).noSpeechThreshold())
                        .isEqualTo(0.9));
    }

    /**
     * 실패 <b>이유</b>까지 본다.
     *
     * <p>{@code hasFailed()} 만 보면 검사가 헐거워진다 — 임곗값 검증을 통째로 지워도 다른 이유로 컨텍스트가 뜨지 않으면 그대로 통과한다. 실제로 이 테스트를 처음 쓸 때
     * {@code gms.transcribe-language} 누락 때문에 모든 케이스가 실패하고 있었고, 그때도 {@code hasFailed()} 는 초록색이었다.
     */
    @Test
    void 임곗값이_1_을_넘으면_기동하지_않는다() {
        runner.withPropertyValues("postclass.transcription.no-speech-threshold=1.5")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasStackTraceContaining("무음 확률 임곗값은 0.0 이상 1.0 이하여야 합니다: 1.5");
                });
    }

    @Test
    void 임곗값이_음수면_기동하지_않는다() {
        runner.withPropertyValues("postclass.transcription.no-speech-threshold=-0.1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasStackTraceContaining("무음 확률 임곗값은 0.0 이상 1.0 이하여야 합니다: -0.1");
                });
    }

    @Test
    void 필터가_꺼져_있어도_잘못된_임곗값은_기동을_막는다() {
        runner.withPropertyValues(
                        "postclass.transcription.hallucination-filter-enabled=false",
                        "postclass.transcription.no-speech-threshold=2.0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasStackTraceContaining("무음 확률 임곗값은 0.0 이상 1.0 이하여야 합니다: 2.0");
                });
    }

    /**
     * {@code GmsProperties} 를 함께 여는 이유는 같은 {@code @Configuration} 이 전사 언어를 그쪽에서 가져오는 오케스트레이션 설정 빈도 만들기 때문이다. 필터 빈만
     * 떼어내려고 설정 클래스를 쪼개지는 않는다 — 두 빈이 같은 프로퍼티 묶음을 읽는다는 사실이 오히려 한곳에 있어야 한다.
     */
    @Configuration(proxyBeanMethods = false)
    @Import(PostClassTranscriptionSettingsConfig.class)
    @EnableConfigurationProperties({PostClassTranscriptionProperties.class, GmsProperties.class})
    static class FilterConfig {}
}
