package com.a105.zani.session.presentation.response;

import com.a105.zani.session.application.screenshare.StartScreenShareResult;

/**
 * 화면 공유 시작 응답.
 *
 * @param sharing 지금 이 요청자가 활성 공유자인지(항상 true — 실패는 예외로 매핑된다)
 * @param sharerParticipantId 활성 공유자의 세션 참가자 ID. TSID라 JS 안전 정수 범위를 넘을 수 있어 문자열로 내린다
 */
public record ScreenShareResponse(boolean sharing, String sharerParticipantId) {

    public static ScreenShareResponse from(StartScreenShareResult result) {
        return new ScreenShareResponse(true, Long.toString(result.participantId()));
    }
}
