package com.a105.zani.postclass.application.port;

import java.util.Optional;

/**
 * 공통 분석 호출 결과. 성공이면 분석을, 실패면 사유를 담는다.
 *
 * <p>{@code Optional} 하나로 돌려주지 않는 이유는 호출부가 재시도 여부를 정해야 하기 때문이다({@link ContentAnalysisFailure} 참고).
 * {@code TipConceptPort} 가 기술적 실패와 근거 없음을 나눠 돌려주는 것과 같은 이유다.
 */
public record ContentAnalysisOutcome(ContentAnalysis analysis, ContentAnalysisFailure failure) {

    public static ContentAnalysisOutcome success(ContentAnalysis analysis) {
        return new ContentAnalysisOutcome(analysis, null);
    }

    public static ContentAnalysisOutcome failed(ContentAnalysisFailure failure) {
        return new ContentAnalysisOutcome(null, failure);
    }

    public Optional<ContentAnalysis> value() {
        return Optional.ofNullable(analysis);
    }
}
