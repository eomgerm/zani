package com.a105.zani.postclass.infrastructure.gms;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공통 분석 호출 설정(S15P11A105-248).
 *
 * <p>요청 바이트 임계는 설정으로 열지 않는다 — 게이트웨이 실측 상한(102,400B)에서 나온 값이라 환경별로 달라질 이유가 없고, 잘못 올리면 게이트웨이가 본문을 잘라 조용히 실패한다(GMS 가이드
 * §4.1).
 *
 * @param maxCompletionTokens 요약과 구간 목록(최대 40개)의 출력 상한. 잘리면 {@code finish_reason=length} 로 JSON 이 깨져 그 세션의 응답 전체를 버린다
 */
@ConfigurationProperties(prefix = "postclass.content-analysis")
public record ContentAnalysisProperties(Integer maxCompletionTokens) {

    /** 값이 없거나 0 이하면 기본값을 쓴다. 0 으로 들어오면 모델이 아무것도 내지 못해 모든 세션의 분석이 조용히 실패한다. */
    public ContentAnalysisProperties {
        if (maxCompletionTokens == null || maxCompletionTokens <= 0) {
            maxCompletionTokens = 4000;
        }
    }
}
