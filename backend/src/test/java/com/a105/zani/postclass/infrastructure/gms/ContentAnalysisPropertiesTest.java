package com.a105.zani.postclass.infrastructure.gms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 값이 비었거나 잘못 들어와도 분석이 조용히 실패하지 않아야 한다. */
class ContentAnalysisPropertiesTest {

    @Test
    void keepsTheConfiguredOutputLimit() {
        assertEquals(3_000, new ContentAnalysisProperties(3_000).maxCompletionTokens());
    }

    @Test
    void fallsBackToTheDefaultWhenTheLimitIsMissing() {
        assertEquals(12_000, new ContentAnalysisProperties(null).maxCompletionTokens());
    }

    /** 0 이면 모델이 아무것도 내지 못해 모든 세션의 분석이 실패한다. 기본값으로 되돌린다. */
    @Test
    void fallsBackToTheDefaultWhenTheLimitIsNotPositive() {
        assertEquals(12_000, new ContentAnalysisProperties(0).maxCompletionTokens());
    }
}
