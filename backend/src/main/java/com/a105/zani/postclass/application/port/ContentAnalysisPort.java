package com.a105.zani.postclass.application.port;

import java.util.Optional;

/**
 * 전사에서 수업 요약과 내용 타임라인을 받아 온다(S15P11A105-248).
 *
 * <p>재시도하지 않는다 — 형제 어댑터({@code GmsTipConceptHttpAdapter})와 같고, 다시 시도할지는 파이프라인 재시도 정책이 정한다(S15P11A105-107).
 *
 * <p>기술적 실패와 스키마 위반을 모두 빈 값으로 돌려준다. 둘 다 "이번 호출로는 저장할 것이 없다"라서 호출부의 처리가 같고, 원인 구분은 어댑터가 로그로 남긴다.
 */
public interface ContentAnalysisPort {

    Optional<ContentAnalysis> analyze(ContentAnalysisRequest request);
}
