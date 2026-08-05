package com.a105.zani.postclass.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 강사 분석의 출력 상한.
 *
 * @param maxCompletionTokens 종합 피드백 + 점수 4개 + 인사이트 4개(제목·근거·제안)가 들어갈 출력 상한. {@code max_tokens} 는 GMS 가 400 으로 거부한다. 잘리면
 *     {@code finish_reason=length} 로 JSON 이 깨져 그 세션의 응답 전체를 버린다
 */
@ConfigurationProperties(prefix = "postclass.instructor-analysis")
public record InstructorAnalysisProperties(Integer maxCompletionTokens) {

    public InstructorAnalysisProperties {
        if (maxCompletionTokens == null || maxCompletionTokens <= 0) {
            maxCompletionTokens = 3_000;
        }
    }
}
