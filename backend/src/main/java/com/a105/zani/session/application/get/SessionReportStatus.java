package com.a105.zani.session.application.get;

/**
 * 목록 화면이 보여줄 리포트 처리 상태.
 *
 * <p><b>파이프라인 단계를 그대로 내리지 않는다.</b> 사후 처리는 여러 단계를 지나지만 목록 카드가 구분해야 하는 것은 "기다려라 / 볼 수 있다 / 실패했다" 셋뿐이다. 중간 단계를 그대로 노출하면
 * 화면이 파이프라인 구현에 묶여, 단계가 하나 늘 때마다 FE 도 고쳐야 한다.
 *
 * <p>단계를 이 값으로 접는 일은 단계를 아는 쪽(postclass)이 맡는다 — {@code SessionReportStatusPort} 의 구현이다. 그래서 session 은 어떤 단계가 있는지 알지
 * 않는다.
 */
public enum SessionReportStatus {

    /** 사후 처리가 시작된 적이 없다. 강사가 메모를 확정해야 작업이 만들어진다. */
    NONE,

    /** 큐에 있거나 전사·분석·검증 중이다. */
    PROCESSING,

    /** 발행됐다. 리포트를 열 수 있다. */
    COMPLETED,

    FAILED
}
