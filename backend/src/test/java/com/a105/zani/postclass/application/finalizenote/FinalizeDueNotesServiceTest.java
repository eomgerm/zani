package com.a105.zani.postclass.application.finalizenote;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.application.InMemoryInstructorNoteRepository;
import com.a105.zani.postclass.domain.model.InstructorNote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalizeDueNotesServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");
    private static final long SESSION_ID = 500L;
    private static final long INSTRUCTOR_PARTICIPANT = 7L;

    private final InMemoryInstructorNoteRepository noteRepository = new InMemoryInstructorNoteRepository();
    private final RecordingFinalizeInactiveNote finalizeInactiveNote =
            new RecordingFinalizeInactiveNote(noteRepository);

    private final FinalizeDueNotesService service =
            new FinalizeDueNotesService(noteRepository, finalizeInactiveNote, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void finalizesADraftThatHasBeenIdleForThirtyMinutes() {
        givenDraftEditedAt(NOW.minus(InstructorNote.INACTIVITY_WINDOW));

        assertEquals(1, service.finalizeDueNotes());
        assertTrue(noteRepository.findBySessionId(SESSION_ID).orElseThrow().isFinalized());
    }

    @Test
    void leavesADraftThatWasEditedInsideTheWindow() {
        givenDraftEditedAt(NOW.minus(InstructorNote.INACTIVITY_WINDOW).plusSeconds(1));

        assertEquals(0, service.finalizeDueNotes());
        assertTrue(finalizeInactiveNote.attempted.isEmpty());
    }

    @Test
    void skipsANoteThatSomeoneElseFinalizedFirst() {
        givenDraftEditedAt(NOW.minus(InstructorNote.INACTIVITY_WINDOW));
        // 대상을 고른 뒤 강사가 `작성 완료`를 눌렀다. 조건부 전환이 실패하므로 확정 건수에 세지 않는다.
        noteRepository.stealFinalizationBeforeNextTransition = true;

        assertEquals(0, service.finalizeDueNotes());
        assertEquals(List.of(SESSION_ID), finalizeInactiveNote.attempted);
    }

    @Test
    void keepsSweepingAfterOneNoteFails() {
        givenDraftEditedAt(NOW.minus(InstructorNote.INACTIVITY_WINDOW).minusSeconds(60), SESSION_ID);
        givenDraftEditedAt(NOW.minus(InstructorNote.INACTIVITY_WINDOW), SESSION_ID + 1);
        finalizeInactiveNote.failFirstAttempt = true;

        assertEquals(1, service.finalizeDueNotes());
        assertEquals(2, finalizeInactiveNote.attempted.size());
    }

    @Test
    void doesNothingWhenNoDraftIsDue() {
        assertEquals(0, service.finalizeDueNotes());
        assertFalse(noteRepository.findBySessionId(SESSION_ID).isPresent());
    }

    private void givenDraftEditedAt(Instant editedAt) {
        givenDraftEditedAt(editedAt, SESSION_ID);
    }

    private void givenDraftEditedAt(Instant editedAt, long sessionId) {
        InstructorNote note = InstructorNote.open(sessionId, INSTRUCTOR_PARTICIPANT);
        note.saveDraft("초안 본문", editedAt);
        noteRepository.save(note);
    }

    /** 스윕이 어떤 세션에 확정을 시도했는지 보기 위한 대역. 실제 전환은 저장소에 그대로 맡긴다. */
    private static final class RecordingFinalizeInactiveNote implements FinalizeInactiveNoteUseCase {

        private final InMemoryInstructorNoteRepository repository;
        private final List<Long> attempted = new ArrayList<>();

        private boolean failFirstAttempt;

        private RecordingFinalizeInactiveNote(InMemoryInstructorNoteRepository repository) {
            this.repository = repository;
        }

        @Override
        public boolean finalizeInactiveNote(Long sessionId) {
            attempted.add(sessionId);
            if (failFirstAttempt && attempted.size() == 1) {
                throw new IllegalStateException("db down");
            }
            return repository.finalizeIfDraft(sessionId, NOW);
        }
    }
}
