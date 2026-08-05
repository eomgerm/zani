package com.a105.zani.postclass.application.port;

/**
 * 전사에서 수업 요약과 내용 타임라인을 받아 온다(S15P11A105-248).
 *
 * <p>어댑터는 재시도하지 않는다 — 형제 어댑터({@code GmsTipConceptHttpAdapter})와 같고, 다시 시도할지는 파이프라인 재시도 정책이 정한다(S15P11A105-107).
 *
 * <p>그 판단을 할 수 있도록 실패를 {@link ContentAnalysisFailure} 로 나눠 돌려준다. 기술적 실패는 기다리면 풀릴 수 있고, 스키마 위반은 같은 요청에 같은 응답이 온다.
 */
public interface ContentAnalysisPort {

    ContentAnalysisOutcome analyze(ContentAnalysisRequest request);
}
