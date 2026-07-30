package com.a105.zani.postclass.application.finalizenote;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.application.InMemoryInstructorNoteRepository;
import com.a105.zani.postclass.domain.exception.ConcurrentNoteOpenException;
import com.a105.zani.postclass.domain.model.InstructorNote;
import com.a105.zani.postclass.domain.model.NoteStatus;
import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.application.exception.SessionNotEndedException;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantQuery;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantResult;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalizeNoteServiceTest {

    private static final long SESSION_ID = 500L;
    private static final long INSTRUCTOR_USER = 11L;
    private static final long INSTRUCTOR_PARTICIPANT = 7L;
    private static final Instant NOW = Instant.parse("2026-07-30T09:30:00Z");
    private static final Instant EARLIER = NOW.minusSeconds(600);

    private final InMemoryInstructorNoteRepository noteRepository = new InMemoryInstructorNoteRepository();
    private final StubResolveEndedParticipant resolveParticipant = new StubResolveEndedParticipant();

    private FinalizeNoteService service;

    @BeforeEach
    void setUp() {
        service = new FinalizeNoteService(resolveParticipant, noteRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void finalizesADraftAndReportsThatThisCallDidIt() {
        givenDraft();

        FinalizeNoteResult result = service.finalizeNote(command());

        assertEquals(NoteStatus.FINALIZED, result.status());
        assertEquals(NOW, result.finalizedAt());
        // 확정 후속 작업(사후 처리 job 생성)은 이 값이 true 인 경로에서만 해야 세션당 한 번이 지켜진다.
        assertTrue(result.finalizedNow());
        assertTrue(noteRepository.findBySessionId(SESSION_ID).orElseThrow().isFinalized());
    }

    @Test
    void keepsTheDraftedContentWhenFinalizing() {
        givenDraft();

        service.finalizeNote(command());

        assertEquals(
                "확정 대상 본문",
                noteRepository.findBySessionId(SESSION_ID).orElseThrow().content());
    }

    @Test
    void treatsARepeatedFinalizeAsSuccessWithoutFinalizingAgain() {
        givenDraft();
        service.finalizeNote(command());

        FinalizeNoteResult result = service.finalizeNote(command());

        assertEquals(NoteStatus.FINALIZED, result.status());
        assertEquals(NOW, result.finalizedAt());
        assertFalse(result.finalizedNow());
    }

    @Test
    void finalizesWithoutADraftWhenTheInstructorSkipsTheNote() {
        FinalizeNoteResult result = service.finalizeNote(command());

        assertEquals(NoteStatus.FINALIZED, result.status());
        assertTrue(result.finalizedNow());
        InstructorNote saved = noteRepository.findBySessionId(SESSION_ID).orElseThrow();
        assertNull(saved.content());
        assertEquals(INSTRUCTOR_PARTICIPANT, saved.instructorParticipantId());
    }

    @Test
    void yieldsToTheAutomaticFinalizationThatWonTheTransition() {
        givenDraft();
        noteRepository.stealFinalizationBeforeNextTransition = true;

        FinalizeNoteResult result = service.finalizeNote(command());

        assertEquals(NoteStatus.FINALIZED, result.status());
        // 확정은 이미 한 번 일어났으므로 성공이지만, 후속 작업을 두 번 하지 않도록 false 로 알린다.
        assertFalse(result.finalizedNow());
    }

    @Test
    void rejectsAParticipantWhoIsNotTheInstructor() {
        resolveParticipant.role = SessionParticipantRole.STUDENT;

        assertThrows(NotSessionInstructorException.class, () -> service.finalizeNote(command()));
    }

    @Test
    void propagatesTheStillLiveSessionRejection() {
        resolveParticipant.failure = new SessionNotEndedException();

        assertThrows(SessionNotEndedException.class, () -> service.finalizeNote(command()));
    }

    @Test
    void surfacesAConcurrentFirstFinalizeAsAConflict() {
        noteRepository.failSaveWithDuplicateKey = true;

        assertThrows(ConcurrentNoteOpenException.class, () -> service.finalizeNote(command()));
    }

    private void givenDraft() {
        InstructorNote note = InstructorNote.open(SESSION_ID, INSTRUCTOR_PARTICIPANT);
        note.saveDraft("확정 대상 본문", EARLIER);
        noteRepository.save(note);
    }

    private FinalizeNoteCommand command() {
        return new FinalizeNoteCommand(SESSION_ID, INSTRUCTOR_USER);
    }

    private static final class StubResolveEndedParticipant implements ResolveEndedSessionParticipantUseCase {

        private SessionParticipantRole role = SessionParticipantRole.INSTRUCTOR;
        private RuntimeException failure;

        @Override
        public ResolveEndedSessionParticipantResult resolve(ResolveEndedSessionParticipantQuery query) {
            if (failure != null) {
                throw failure;
            }
            return new ResolveEndedSessionParticipantResult(INSTRUCTOR_PARTICIPANT, role);
        }
    }
}
