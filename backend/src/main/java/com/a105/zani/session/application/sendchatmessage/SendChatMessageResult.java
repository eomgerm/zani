package com.a105.zani.session.application.sendchatmessage;

/**
 * 전송 처리 결과.
 *
 * <p>STOMP 는 요청·응답이 아니라 돌려줄 상태 코드가 없다. 그래서 거절도 예외가 아니라 결과로 표현하고, 보낸 사람에게 알리는 것까지 유스케이스가 끝낸다.
 *
 * @param eventId 저장된 메시지의 식별자. 재시도로 걸러진 경우에는 처음 저장 때 부여된 값이고, 거절된 경우에는 null
 * @param duplicate 같은 {@code clientEventId} 가 이미 처리돼 저장을 건너뛴 경우 true
 * @param rejectionReason 받아들이지 않은 사유 코드. 정상 처리면 null
 */
public record SendChatMessageResult(String eventId, boolean duplicate, String rejectionReason) {

    public static SendChatMessageResult sent(String eventId) {
        return new SendChatMessageResult(eventId, false, null);
    }

    public static SendChatMessageResult duplicate(String eventId) {
        return new SendChatMessageResult(eventId, true, null);
    }

    public static SendChatMessageResult rejected(String reason) {
        return new SendChatMessageResult(null, false, reason);
    }

    public boolean rejected() {
        return rejectionReason != null;
    }
}
