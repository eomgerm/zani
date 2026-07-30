package com.a105.zani.session.presentation.response;

import com.a105.zani.session.application.checkjoinable.CheckJoinableResult;
import com.a105.zani.session.domain.model.SessionStatus;

/**
 * @param sessionId TSID 는 JS 안전 정수 범위를 넘어 브라우저에서 반올림되므로 문자열로 내보낸다
 * @param remainingSeats 확인한 순간의 남은 자리. 실제 입장까지 줄어들 수 있어 표시용이다
 */
public record JoinableSessionResponse(String sessionId, String inviteCode, SessionStatus status, int remainingSeats) {

    public static JoinableSessionResponse from(CheckJoinableResult result) {
        return new JoinableSessionResponse(
                String.valueOf(result.sessionId()), result.inviteCode(), result.status(), result.remainingSeats());
    }
}
