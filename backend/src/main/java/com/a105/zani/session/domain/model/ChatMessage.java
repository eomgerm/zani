package com.a105.zani.session.domain.model;

/**
 * 수업 중 주고받는 공개 채팅 메시지.
 *
 * <p>1:1(비공개) 채팅은 범위에서 제외됐다(2026-07-30 확정). {@code chat_messages} 테이블에는 수신자·채널 구분 컬럼이 남아 있지만 이 모델은 공개 메시지만 다루고, 영속화 시
 * 채널을 PUBLIC 으로 고정한다.
 *
 * <p>{@code occurredOffsetMs} 는 수업 시작으로부터 흐른 밀리초다. 리포트 타임라인이 이 값을 축으로 쓰므로 클라이언트가 보낸 시각이 아니라 서버가 계산한 값만 담는다 — 시계가 틀어진
 * 브라우저 하나가 전체 타임라인을 흔들 수 있다.
 */
public class ChatMessage {

    /** {@code chat_messages.content} 컬럼 상한과 같다. */
    public static final int MAX_CONTENT_LENGTH = 1000;

    private final Long id;
    private final Long sessionId;
    private final Long senderParticipantId;
    private final String content;
    private final long occurredOffsetMs;

    private ChatMessage(Long id, Long sessionId, Long senderParticipantId, String content, long occurredOffsetMs) {
        this.id = id;
        this.sessionId = sessionId;
        this.senderParticipantId = senderParticipantId;
        this.content = content;
        this.occurredOffsetMs = occurredOffsetMs;
    }

    /**
     * 새 공개 메시지. 사용자 입력 검증은 presentation 계층이 하고, 여기서는 계약 위반만 막는다 — 컬럼 상한을 넘긴 값이 여기까지 오면 DB 가 잘라내거나 거부하므로 그 전에 드러나야 한다.
     */
    public static ChatMessage post(
            Long id, Long sessionId, Long senderParticipantId, String content, long occurredOffsetMs) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("채팅 본문은 비어 있을 수 없습니다.");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("채팅 본문은 " + MAX_CONTENT_LENGTH + "자를 넘을 수 없습니다.");
        }
        if (occurredOffsetMs < 0) {
            throw new IllegalArgumentException("채팅 시각은 수업 시작보다 이를 수 없습니다.");
        }
        return new ChatMessage(id, sessionId, senderParticipantId, content, occurredOffsetMs);
    }

    public static ChatMessage reconstitute(
            Long id, Long sessionId, Long senderParticipantId, String content, long occurredOffsetMs) {
        return new ChatMessage(id, sessionId, senderParticipantId, content, occurredOffsetMs);
    }

    public Long id() {
        return id;
    }

    public Long sessionId() {
        return sessionId;
    }

    public Long senderParticipantId() {
        return senderParticipantId;
    }

    public String content() {
        return content;
    }

    public long occurredOffsetMs() {
        return occurredOffsetMs;
    }
}
