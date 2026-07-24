package com.a105.zani.session.application.presence;

/** heartbeat 처리 결과로 참가자에게 돌려주는 재연결·세션 상태 신호. */
public enum ReconnectStatus {
    /** 정상 접속 중. */
    CONNECTED,
    /** 강사가 유예 시간 안에 복귀함. */
    RECONNECTED,
    /** 강사가 이탈해 5분 유예가 진행 중. */
    GRACE_PERIOD,
    /** 학생이 연결을 종료함. */
    DISCONNECTED,
    /** 강사가 유예 시간 내 복귀하지 못해 세션이 종료됨. */
    SESSION_ENDED
}
