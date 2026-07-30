package com.a105.zani.session.domain.model;

/**
 * 세션이 종료된 사유. 가이드 §12의 세 종료 경로가 모두 같은 유스케이스를 호출하며, 사유만 다르게 전달한다.
 *
 * <p>세션이 이 값을 들고 저장되므로 도메인에 둔다. 로그로만 남기면 왜 끝났는지가 수업이 끝난 뒤 사라진다.
 */
public enum SessionEndReason {
    /** 강사가 강의실에서 수업을 직접 종료했다. */
    INSTRUCTOR_REQUEST,
    /** 최대 수업 시간(3시간)에 도달했다. */
    MAX_DURATION_REACHED,
    /** 강사가 유예 시간 안에 복귀하지 않았다. */
    INSTRUCTOR_ABSENT
}
