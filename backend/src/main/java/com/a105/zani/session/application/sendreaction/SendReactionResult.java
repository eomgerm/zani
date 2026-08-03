package com.a105.zani.session.application.sendreaction;

/**
 * @param eventId 서버가 부여한 식별자. 거절된 경우 null
 * @param rejectionReason 받아들이지 않은 사유 코드. 정상 처리면 null
 */
public record SendReactionResult(String eventId, String rejectionReason) {

    public static SendReactionResult sent(String eventId) {
        return new SendReactionResult(eventId, null);
    }

    public static SendReactionResult rejected(String reason) {
        return new SendReactionResult(null, reason);
    }

    public boolean rejected() {
        return rejectionReason != null;
    }
}
