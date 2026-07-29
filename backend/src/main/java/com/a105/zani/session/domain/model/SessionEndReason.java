package com.a105.zani.session.domain.model;

/** 세션이 종료된 사유. 가이드 §12의 세 종료 경로가 모두 같은 유스케이스를 호출하며, 사유만 다르게 전달한다. 종료 후 감사·리포트에 쓰이므로 세션과 함께 저장한다. */
public enum SessionEndReason {
    /** 강사가 강의실에서 수업을 직접 종료했다. */
    INSTRUCTOR_REQUEST,
    /** 최대 수업 시간(3시간)에 도달했다. */
    MAX_DURATION_REACHED,
    /** 강사가 유예 시간 안에 복귀하지 않았다. */
    INSTRUCTOR_ABSENT,
    /** 시작되지 않은 채 방치된 준비 상태 세션을 정리했다. */
    ABANDONED_BEFORE_START
}
