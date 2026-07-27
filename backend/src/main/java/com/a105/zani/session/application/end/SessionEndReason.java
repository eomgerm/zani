package com.a105.zani.session.application.end;

/**
 * 세션이 종료된 사유. 가이드 §12의 종료 경로가 모두 같은 유스케이스를 호출하며, 사유만 다르게 전달한다. 실제로 발생하는 사유만 정의한다. 강사 명시 종료 API가 생기면 그때
 * INSTRUCTOR_REQUEST를 추가한다.
 */
public enum SessionEndReason {
    /** 최대 수업 시간(3시간)에 도달했다. */
    MAX_DURATION_REACHED,
    /** 강사가 유예 시간 안에 복귀하지 않았다. */
    INSTRUCTOR_ABSENT
}
