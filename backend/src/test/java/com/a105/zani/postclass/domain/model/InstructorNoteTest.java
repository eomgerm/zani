package com.a105.zani.postclass.domain.model;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.domain.exception.InvalidNoteContentException;
import com.a105.zani.postclass.domain.exception.NoteAlreadyFinalizedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstructorNoteTest {

    private static final long SESSION_ID = 500L;
    private static final long INSTRUCTOR_PARTICIPANT_ID = 7L;
    private static final Instant FIRST_EDIT = Instant.parse("2026-07-30T09:00:00Z");
    private static final Instant SECOND_EDIT = FIRST_EDIT.plusSeconds(600);
    private static final int CONTENT_MAX_LENGTH = 5_000;

    @Test
    void opensWithoutContentAndWithoutAnInactivityBaseline() {
        InstructorNote note = InstructorNote.open(SESSION_ID, INSTRUCTOR_PARTICIPANT_ID);

        assertEquals(NoteStatus.DRAFT, note.status());
        assertNull(note.content());
        assertNull(note.lastEditedAt());
    }

    @Test
    void keepsTheLatestContentAndMovesTheInactivityBaseline() {
        InstructorNote note = InstructorNote.open(SESSION_ID, INSTRUCTOR_PARTICIPANT_ID);
        note.saveDraft("첫 입력", FIRST_EDIT);

        note.saveDraft("이어 쓴 입력", SECOND_EDIT);

        assertEquals("이어 쓴 입력", note.content());
        // 마지막 입력 시각이 30분 비활성 타이머의 기준이다(NOTE-002).
        assertEquals(SECOND_EDIT, note.lastEditedAt());
    }

    @Test
    void trimsTheContentAndAcceptsTheMaximumLength() {
        InstructorNote note = InstructorNote.open(SESSION_ID, INSTRUCTOR_PARTICIPANT_ID);

        note.saveDraft("  앞뒤 공백  ", FIRST_EDIT);
        assertEquals("앞뒤 공백", note.content());

        note.saveDraft("가".repeat(CONTENT_MAX_LENGTH), SECOND_EDIT);
        assertEquals(CONTENT_MAX_LENGTH, note.content().length());
    }

    @Test
    void rejectsContentLongerThanTheMaximum() {
        InstructorNote note = InstructorNote.open(SESSION_ID, INSTRUCTOR_PARTICIPANT_ID);
        String tooLong = "가".repeat(CONTENT_MAX_LENGTH + 1);

        assertThrows(InvalidNoteContentException.class, () -> note.saveDraft(tooLong, FIRST_EDIT));
    }

    @Test
    void treatsAnEmptyContentAsClearedButStillResetsTheTimer() {
        // 메모 없이 완료하는 경로가 있으므로 빈 본문도 유효하다(FRD §16).
        InstructorNote note = InstructorNote.open(SESSION_ID, INSTRUCTOR_PARTICIPANT_ID);
        note.saveDraft("첫 입력", FIRST_EDIT);

        note.saveDraft("   ", SECOND_EDIT);

        assertNull(note.content());
        assertEquals(SECOND_EDIT, note.lastEditedAt());
    }

    @Test
    void refusesToEditAFinalizedNote() {
        InstructorNote finalized =
                InstructorNote.finalizedWithoutDraft(SESSION_ID, INSTRUCTOR_PARTICIPANT_ID, FIRST_EDIT);

        assertTrue(finalized.isFinalized());
        assertThrows(NoteAlreadyFinalizedException.class, () -> finalized.saveDraft("확정 후 수정", SECOND_EDIT));
    }

    @Test
    void opensTheFinalizedWithoutDraftNoteWithoutContent() {
        InstructorNote note = InstructorNote.finalizedWithoutDraft(SESSION_ID, INSTRUCTOR_PARTICIPANT_ID, FIRST_EDIT);

        assertEquals(NoteStatus.FINALIZED, note.status());
        assertEquals(FIRST_EDIT, note.finalizedAt());
        assertNull(note.content());
        assertNull(note.lastEditedAt());
    }
}
