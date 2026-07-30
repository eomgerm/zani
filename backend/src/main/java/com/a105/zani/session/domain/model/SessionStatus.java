package com.a105.zani.session.domain.model;

/**
 * 세션 생명주기. 확정 흐름은 PREPARING → LIVE → ENDING → NOTE_PENDING → ENDED 이며 역행하지 않는다.
 *
 * <p>상태를 "종료됐는가" 하나로 묻지 않고 {@link #isLive()}·{@link #isClosed()} 로 나눈 이유가 있다. LIVE 와 ENDED 사이에 정리 단계가 생기면서 "ENDED 가
 * 아니다"가 더 이상 "수업이 살아 있다"를 뜻하지 않게 됐다. 이 구분이 없으면 정리 중인 세션에 미디어 토큰이 계속 발급된다.
 */
public enum SessionStatus {
    /** 강사가 만들었지만 아직 시작하지 않았다. 초대 코드로 입장할 수 없다. */
    PREPARING,
    /** 수업이 진행 중이다. 학생 입장과 접속 집계가 열리는 유일한 상태다. */
    LIVE,
    /** 종료 요청을 받아 정리 중이다. */
    ENDING,
    /** 정리가 끝나 강사 메모를 기다린다. */
    NOTE_PENDING,
    /** 수업과 후처리가 모두 끝났다. */
    ENDED;

    /** 수업이 진행 중인가. 학생 입장·접속 집계는 이 상태에서만 열린다. */
    public boolean isLive() {
        return this == LIVE;
    }

    /** 종료 요청 이후인가. 참가자를 더 받지 않고 미디어도 내주지 않는다. */
    public boolean isClosed() {
        return this == ENDING || this == NOTE_PENDING || this == ENDED;
    }
}
