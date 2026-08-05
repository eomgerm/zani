package com.a105.zani.postclass.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 학생별 분석의 출력 상한.
 *
 * @param maxCompletionTokens 요약 + 추천 5개 + 4지선다 5문항과 해설이 들어갈 출력 상한. {@code max_tokens} 는 GMS 가 400 으로 거부한다. 잘리면
 *     {@code finish_reason=length} 로 JSON 이 깨져 그 학생의 응답 전체를 버린다
 */
@ConfigurationProperties(prefix = "postclass.student-analysis")
public record StudentAnalysisProperties(Integer maxCompletionTokens) {

    public StudentAnalysisProperties {
        if (maxCompletionTokens == null || maxCompletionTokens <= 0) {
            maxCompletionTokens = 3_000;
        }
    }
}
