package com.a105.zani.postclass.application.runsessionanalysis;

/**
 * 세션 하나의 분석을 공개까지 끌고 간다(S15P11A105-304).
 *
 * <p>단계 구현은 247·248·249·250 이 각자 갖고 있고, 이 유스케이스는 그 사이의 배선과 공개 시점만 맡는다.
 *
 * <p><b>실행권을 잡은 뒤에 부른다.</b> 이 호출은 GMS 를 여러 번 부르므로 수십 분이 걸릴 수 있다. 선점 없이 부르면 같은 세션을 두 실행이 동시에 분석한다
 * ({@code TryClaimAnalysisUseCase}).
 */
public interface RunSessionAnalysisUseCase {

    /**
     * 공통 분석 → 학생별 분석 → 강사 분석 → 공개 순서로 진행한다.
     *
     * <p>예외를 올리지 않는다. 실패는 재시도 정책에 기록하고 조용히 끝낸다 — 호출자는 실행기 스레드라 올려도 받을 사람이 없다.
     */
    void run(Long sessionId);
}
