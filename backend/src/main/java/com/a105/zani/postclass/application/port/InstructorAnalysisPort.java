package com.a105.zani.postclass.application.port;

import java.util.Optional;

/**
 * 세션 하나의 질문 수·종합 피드백·분야별 평가·수업 인사이트를 LLM 한 번으로 받아 온다.
 *
 * <p>timeout·401·402·429·5xx·스키마 위반을 전부 빈 값 하나로 돌려준다. <b>세션 단위</b>의 대응이 같기 때문이다 — 그 세션을 실패로 남긴다. 같은 실행 안에서 다시 부르지 않는다.
 *
 * <p><b>알려진 한계 — 파이프라인 배선 시 이 계약을 넓혀야 한다.</b> 사후 파이프라인의 재시도 정책({@code PostClassRetryPolicy}, S15P11A105-107)은
 * {@code retryable} 판단을 단계를 수행한 쪽에 맡긴다. 그런데 <b>단계 단위</b>에서는 대응이 갈린다 — 429·5xx·timeout 은 기다리면 풀리지만 스키마
 * 위반·refusal·401·402·길이 가드 초과는 몇 번을 보내도 같다. 이 포트가 사유를 하나로 접고 있어 그 판단에 쓸 재료가 남지 않는다.
 *
 * <p>지금은 이 유스케이스를 부르는 진입점이 없어 소비자도 없다. 배선하는 일감이 반환 계약을 <i>성공 / 일시적 실패 / 영구 실패</i> 세 갈래로 넓혀야 한다. 멱등이 세션 단위라 재시도 비용은 LLM
 * 호출 1회다.
 */
public interface InstructorAnalysisPort {

    Optional<InstructorAnalysis> analyze(InstructorAnalysisRequest request);
}
