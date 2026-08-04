package com.a105.zani.postclass.infrastructure.gms;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.a105.zani.postclass.application.port.AnalyzedSection;
import com.a105.zani.postclass.application.port.ContentAnalysis;
import com.a105.zani.postclass.application.port.ContentAnalysisFailure;
import com.a105.zani.postclass.application.port.ContentAnalysisLine;
import com.a105.zani.postclass.application.port.ContentAnalysisOutcome;
import com.a105.zani.postclass.application.port.ContentAnalysisPort;
import com.a105.zani.postclass.application.port.ContentAnalysisRequest;

/**
 * GMS 크레딧을 쓰지 않고 개발·테스트할 때 쓰는 공통 분석 어댑터(S15P11A105-248).
 *
 * <p>{@code gms.mock-enabled} 로 실제 어댑터와 배타적으로 갈라진다. 조건이 겹치면 {@link ContentAnalysisPort} 빈이 둘이라 기동이 실패한다. 운영에서 이 어댑터가 뜨는
 * 사고는 {@code GmsMockProfileGuard} 가 기동 시점에 막는다.
 *
 * <p>전사의 첫 줄과 마지막 줄로 구간 하나를 만든다. 고정 오프셋을 쓰면 수업 길이와 어긋나 적재 단계의 범위 검증에 걸려, 목업 환경에서 리포트가 늘 실패한다.
 */
@Component
@ConditionalOnProperty(prefix = "gms", name = "mock-enabled", havingValue = "true", matchIfMissing = true)
public class GmsContentAnalysisMockAdapter implements ContentAnalysisPort {

    private static final Logger log = LoggerFactory.getLogger(GmsContentAnalysisMockAdapter.class);

    static final String MOCK_CLASS_SUMMARY = "예시로 만든 수업 요약입니다.";
    static final String MOCK_SECTION_TITLE = "예시 구간";
    static final String MOCK_SECTION_SUMMARY = "예시로 만든 구간 요약입니다.";

    @Override
    public ContentAnalysisOutcome analyze(ContentAnalysisRequest request) {
        if (request == null || request.lines() == null || request.lines().isEmpty()) {
            return ContentAnalysisOutcome.failed(ContentAnalysisFailure.UNUSABLE_RESPONSE);
        }
        List<ContentAnalysisLine> lines = request.lines();
        long startOffsetMs = lines.getFirst().startOffsetMs();
        long endOffsetMs = Math.min(lines.getLast().endOffsetMs(), request.classDurationMs());
        if (endOffsetMs <= startOffsetMs) {
            return ContentAnalysisOutcome.failed(ContentAnalysisFailure.UNUSABLE_RESPONSE);
        }

        log.info("Mock content analysis returned for a {}ms class", request.classDurationMs());
        return ContentAnalysisOutcome.success(new ContentAnalysis(
                MOCK_CLASS_SUMMARY,
                List.of(new AnalyzedSection(MOCK_SECTION_TITLE, MOCK_SECTION_SUMMARY, startOffsetMs, endOffsetMs))));
    }
}
