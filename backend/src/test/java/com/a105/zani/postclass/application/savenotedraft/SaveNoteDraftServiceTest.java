package com.a105.zani.postclass.application.savenotedraft;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.application.InMemoryInstructorNoteRepository;
import com.a105.zani.postclass.domain.exception.ConcurrentNoteOpenException;
import com.a105.zani.postclass.domain.exception.InvalidNoteContentException;
import com.a105.zani.postclass.domain.exception.NoteAlreadyFinalizedException;
import com.a105.zani.postclass.domain.model.InstructorNote;
import com.a105.zani.postclass.domain.model.NoteStatus;
import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.application.exception.SessionNotEndedException;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantQuery;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantResult;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveNoteDraftServiceTest {

    private static final long SESSION_ID = 500L;
    private static final long INSTRUCTOR_USER = 11L;
    private static final long INSTRUCTOR_PARTICIPANT = 7L;
    private static final Instant NOW = Instant.parse("2026-07-30T09:00:00Z");

    private final InMemoryInstructorNoteRepository noteRepository = new InMemoryInstructorNoteRepository();
    private final StubResolveEndedParticipant resolveParticipant = new StubResolveEndedParticipant();

    private SaveNoteDraftService service;

    @BeforeEach
    void setUp() {
        service = new SaveNoteDraftService(resolveParticipant, noteRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void savesTheDraftAndStampsTheEditTime() {
        SaveNoteDraftResult result = service.save(command("재귀 종료 조건에서 절반이 헷갈려했다"));

        assertEquals(NoteStatus.DRAFT, result.status());
        assertEquals(NOW, result.lastEditedAt());
        InstructorNote saved = noteRepository.findBySessionId(SESSION_ID).orElseThrow();
        assertEquals(INSTRUCTOR_PARTICIPANT, saved.instructorParticipantId());
        assertEquals("재귀 종료 조건에서 절반이 헷갈려했다", saved.content());
    }

    @Test
    void replacesTheContentOfAnExistingDraft() {
        service.save(command("첫 입력"));

        service.save(command("이어 쓴 입력"));

        assertEquals(
                "이어 쓴 입력",
                noteRepository.findBySessionId(SESSION_ID).orElseThrow().content());
    }

    @Test
    void acceptsAnEmptyContentWhenTheInstructorClearsTheNote() {
        service.save(command("첫 입력"));

        service.save(command("   "));

        assertNull(noteRepository.findBySessionId(SESSION_ID).orElseThrow().content());
    }

    @Test
    void acceptsAMissingContent() {
        SaveNoteDraftResult result = service.save(command(null));

        assertEquals(NoteStatus.DRAFT, result.status());
        assertNull(noteRepository.findBySessionId(SESSION_ID).orElseThrow().content());
    }

    @Test
    void rejectsContentLongerThanTheMaximum() {
        SaveNoteDraftCommand command = command("가".repeat(5_001));

        assertThrows(InvalidNoteContentException.class, () -> service.save(command));
    }

    @Test
    void rejectsAParticipantWhoIsNotTheInstructor() {
        resolveParticipant.role = SessionParticipantRole.STUDENT;
        SaveNoteDraftCommand command = command("학생 요청");

        assertThrows(NotSessionInstructorException.class, () -> service.save(command));
    }

    @Test
    void propagatesTheStillLiveSessionRejection() {
        resolveParticipant.failure = new SessionNotEndedException();
        SaveNoteDraftCommand command = command("진행 중 수업");

        assertThrows(SessionNotEndedException.class, () -> service.save(command));
    }

    @Test
    void refusesToEditAFinalizedNote() {
        noteRepository.save(InstructorNote.finalizedWithoutDraft(SESSION_ID, INSTRUCTOR_PARTICIPANT, NOW));
        SaveNoteDraftCommand command = command("확정 후 수정");

        assertThrows(NoteAlreadyFinalizedException.class, () -> service.save(command));
    }

    @Test
    void refusesToOverwriteAFinalizationThatLandedWhileSaving() {
        service.save(command("첫 입력"));
        // 초안을 읽은 뒤 저장하기 전에 30분 비활성 확정이 끼어들었다. 덮어쓰면 확정이 DRAFT 로 되돌아간다.
        noteRepository.stealFinalizationBeforeNextSave = true;
        SaveNoteDraftCommand command = command("확정과 겹친 입력");

        assertThrows(NoteAlreadyFinalizedException.class, () -> service.save(command));
        assertTrue(noteRepository.findBySessionId(SESSION_ID).orElseThrow().isFinalized());
    }

    @Test
    void surfacesAConcurrentFirstSaveAsAConflict() {
        // 강사가 창을 두 개 열어 두고 첫 자동 저장이 동시에 나가면 한쪽이 UK_INSTRUCTOR_NOTES_SESSION 에 걸린다.
        noteRepository.failSaveWithDuplicateKey = true;
        SaveNoteDraftCommand command = command("동시 저장");

        assertThrows(ConcurrentNoteOpenException.class, () -> service.save(command));
    }

    private SaveNoteDraftCommand command(String content) {
        return new SaveNoteDraftCommand(SESSION_ID, INSTRUCTOR_USER, content);
    }

    private final class StubResolveEndedParticipant implements ResolveEndedSessionParticipantUseCase {

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
