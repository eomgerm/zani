package com.a105.zani.postclass.domain.model;

import java.time.Instant;

import com.a105.zani.postclass.domain.exception.InvalidNoteContentException;
import com.a105.zani.postclass.domain.exception.NoteAlreadyFinalizedException;

/**
 * 수업 하나에 대한 강사의 사후 메모(FRD §16). 세션당 한 행이며(UK_INSTRUCTOR_NOTES_SESSION) 본문도 하나다 — 구간을 나누지 않는다. 개념명·강조 이유·학생이 다시 볼 포인트를
 * 자유 형식으로 담고, 사후 분석에는 전사·영상·집중도 이벤트와 함께 이 본문 한 덩어리가 넘어간다.
 *
 * <p>확정은 수동 완료와 30분 비활성 두 경로에서 오지만 <b>단 한 번만</b> 일어나야 한다. 그 단일성은 이 객체가 아니라 저장소의 원자
 * 전환({@code InstructorNoteRepository#finalizeIfDraft})이 지킨다 — 두 경로가 서로 다른 트랜잭션에서 동시에 들어오면 메모리 상태 검사로는 막을 수 없다. 여기서는 이미
 * 확정된 메모를 수정하려는 시도만 거절한다.
 */
public final class InstructorNote {

    private static final int CONTENT_MAX_LENGTH = 5_000;

    private final Long id;
    private final Long sessionId;
    private final Long instructorParticipantId;
    private final NoteStatus status;
    private final Instant finalizedAt;
    private String content;
    private Instant lastEditedAt;

    private InstructorNote(
            Long id,
            Long sessionId,
            Long instructorParticipantId,
            NoteStatus status,
            String content,
            Instant lastEditedAt,
            Instant finalizedAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.instructorParticipantId = instructorParticipantId;
        this.status = status;
        this.content = content;
        this.lastEditedAt = lastEditedAt;
        this.finalizedAt = finalizedAt;
    }

    /** 강사의 첫 입력으로 메모를 연다. 이 시점부터 30분 비활성 타이머의 기준이 생긴다. */
    public static InstructorNote open(Long sessionId, Long instructorParticipantId) {
        return new InstructorNote(null, sessionId, instructorParticipantId, NoteStatus.DRAFT, null, null, null);
    }

    /** 메모 없이 완료하는 경로(FRD §16). 초안을 한 번도 저장하지 않은 세션도 확정 행을 남겨야 사후 처리가 시작된다. */
    public static InstructorNote finalizedWithoutDraft(
            Long sessionId, Long instructorParticipantId, Instant finalizedAt) {
        return new InstructorNote(
                null, sessionId, instructorParticipantId, NoteStatus.FINALIZED, null, null, finalizedAt);
    }

    /** 저장소에서 읽어 되살린다. */
    public static InstructorNote reconstitute(
            Long id,
            Long sessionId,
            Long instructorParticipantId,
            NoteStatus status,
            String content,
            Instant lastEditedAt,
            Instant finalizedAt) {
        return new InstructorNote(id, sessionId, instructorParticipantId, status, content, lastEditedAt, finalizedAt);
    }

    /**
     * 본문을 이번 입력분으로 바꾸고 마지막 입력 시각을 갱신한다(NOTE-002: 입력 감지 시 30분 타이머 초기화).
     *
     * <p>빈 본문도 유효하다 — 강사가 쓰던 내용을 지운 상태이며, 메모 없이 완료하는 경로가 따로 있다(FRD §16). 지운 뒤에도 타이머는 초기화된다.
     *
     * @throws NoteAlreadyFinalizedException 이미 확정된 메모
     * @throws InvalidNoteContentException 본문이 5000자를 넘음
     */
    public void saveDraft(String content, Instant editedAt) {
        if (status == NoteStatus.FINALIZED) {
            throw new NoteAlreadyFinalizedException();
        }
        String trimmed = content == null ? null : content.trim();
        if (trimmed != null && trimmed.length() > CONTENT_MAX_LENGTH) {
            throw new InvalidNoteContentException();
        }
        this.content = trimmed == null || trimmed.isEmpty() ? null : trimmed;
        this.lastEditedAt = editedAt;
    }

    public boolean isFinalized() {
        return status == NoteStatus.FINALIZED;
    }

    public Long id() {
        return id;
    }

    public Long sessionId() {
        return sessionId;
    }

    public Long instructorParticipantId() {
        return instructorParticipantId;
    }

    public NoteStatus status() {
        return status;
    }

    public String content() {
        return content;
    }

    public Instant lastEditedAt() {
        return lastEditedAt;
    }

    public Instant finalizedAt() {
        return finalizedAt;
    }
}
