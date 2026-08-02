package com.a105.zani.session.application.moderation;

/**
 * @param muted 이 호출 뒤 대상이 음소거 상태인지. 실패는 예외로 나가므로 여기까지 오면 항상 true 다
 * @param alreadyMuted 이미 음소거였는지. 같은 요청을 다시 보낸 경우이며 이력을 늘리지 않았다는 뜻이다
 */
public record MuteParticipantResult(boolean muted, boolean alreadyMuted) {

    public static MuteParticipantResult changed() {
        return new MuteParticipantResult(true, false);
    }

    public static MuteParticipantResult unchanged() {
        return new MuteParticipantResult(true, true);
    }
}
