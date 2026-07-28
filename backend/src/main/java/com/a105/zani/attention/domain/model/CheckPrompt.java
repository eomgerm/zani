package com.a105.zani.attention.domain.model;

/**
 * 학생에게 띄운 프롬프트 한 건과 그 답.
 *
 * <p>프롬프트를 띄울지는 브라우저가 판정하므로(75) 서버에는 표시 시점의 행이 없다. 답이 도착할 때 비로소 이 기록이 만들어진다. 그래서 같은 프롬프트에 대한 재시도를 알아보는 기준은 (참가자, 종류, 표시
 * 시각)이다. 한 학생에게 같은 종류의 프롬프트가 같은 밀리초에 두 번 뜰 수는 없다.
 */
public final class CheckPrompt {

    private final Long id;
    private final Long sessionId;
    private final Long participantId;
    private final PromptKind kind;
    private final CheckPromptStatus status;
    private final PromptAnswer answer;
    private final long shownOffsetMs;
    private final Long respondedOffsetMs;

    private CheckPrompt(
            Long id,
            Long sessionId,
            Long participantId,
            PromptKind kind,
            CheckPromptStatus status,
            PromptAnswer answer,
            long shownOffsetMs,
            Long respondedOffsetMs) {
        this.id = id;
        this.sessionId = sessionId;
        this.participantId = participantId;
        this.kind = kind;
        this.status = status;
        this.answer = answer;
        this.shownOffsetMs = shownOffsetMs;
        this.respondedOffsetMs = respondedOffsetMs;
    }

    /** 학생의 답으로 기록을 만든다. 무응답은 TIMEOUT 으로 남고 응답 시각을 갖지 않는다. */
    public static CheckPrompt respond(
            Long sessionId,
            Long participantId,
            PromptKind kind,
            PromptAnswer answer,
            long shownOffsetMs,
            long respondedOffsetMs) {
        boolean timedOut = answer == PromptAnswer.NO_RESPONSE;
        return new CheckPrompt(
                null,
                sessionId,
                participantId,
                kind,
                timedOut ? CheckPromptStatus.TIMEOUT : CheckPromptStatus.RESPONDED,
                answer,
                shownOffsetMs,
                timedOut ? null : respondedOffsetMs);
    }

    /** 저장소에서 읽어 되살린다. */
    public static CheckPrompt reconstitute(
            Long id,
            Long sessionId,
            Long participantId,
            PromptKind kind,
            CheckPromptStatus status,
            PromptAnswer answer,
            long shownOffsetMs,
            Long respondedOffsetMs) {
        return new CheckPrompt(id, sessionId, participantId, kind, status, answer, shownOffsetMs, respondedOffsetMs);
    }

    public Long id() {
        return id;
    }

    public Long sessionId() {
        return sessionId;
    }

    public Long participantId() {
        return participantId;
    }

    public PromptKind kind() {
        return kind;
    }

    public CheckPromptStatus status() {
        return status;
    }

    public PromptAnswer answer() {
        return answer;
    }

    public long shownOffsetMs() {
        return shownOffsetMs;
    }

    public Long respondedOffsetMs() {
        return respondedOffsetMs;
    }
}
