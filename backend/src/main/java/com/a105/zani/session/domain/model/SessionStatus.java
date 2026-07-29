package com.a105.zani.session.domain.model;

/**
 * 세션의 업무 생명주기. 가이드 §4의 목표 전이는 {@code PREPARING → LIVE → ENDING → NOTE_PENDING}이며, 이후 분석 파이프라인 진행 상태는
 * {@link SessionAnalysisStatus}가 따로 표현한다.
 */
public enum SessionStatus {
    /** 강사가 방을 만들었지만 아직 시작하지 않았다. 강사만 미디어 토큰을 받을 수 있고 학생 입장은 막힌다. */
    PREPARING,
    /** 수업이 실제로 시작됐다. 초대 코드로 학생이 입장할 수 있는 유일한 상태다. */
    LIVE,
    /** 종료 절차가 시작됐다. 신규 입장·토큰 발급을 차단하고 Egress·Room 정리를 진행한다. */
    ENDING,
    /** Room 정리가 끝나고 강사 메모 작성을 기다린다. */
    NOTE_PENDING,
    /** 최종 종료. 더 이상 어떤 참여도 받지 않는다. */
    ENDED
}
