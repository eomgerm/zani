package com.a105.zani.postclass.application.finalizenote;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.application.InMemoryInstructorNoteRepository;
import com.a105.zani.postclass.application.InMemoryPipelineJobPort;
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
    private final InMemoryPipelineJobPort pipelineJobPort = new InMemoryPipelineJobPort();
    private final StubResolveEndedParticipant resolveParticipant = new StubResolveEndedParticipant();

    private FinalizeNoteService service;

    @BeforeEach
    void setUp() {
        service = new FinalizeNoteService(
                resolveParticipant, noteRepository, pipelineJobPort, Clock.fixed(NOW, ZoneOffset.UTC));
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
        // 확정이 곧 사후 처리의 시작점이다(NOTE-004).
        assertEquals(List.of(SESSION_ID), pipelineJobPort.enqueuedSessionIds);
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
        // 두 번 눌러도 작업은 한 건이다(§17.1 처리 순서의 시작점이 두 번 열리지 않는다).
        assertEquals(List.of(SESSION_ID), pipelineJobPort.enqueuedSessionIds);
    }

    @Test
    void finalizesWithoutADraftWhenTheInstructorSkipsTheNote() {
        FinalizeNoteResult result = service.finalizeNote(command());

        assertEquals(NoteStatus.FINALIZED, result.status());
        assertTrue(result.finalizedNow());
        InstructorNote saved = noteRepository.findBySessionId(SESSION_ID).orElseThrow();
        assertNull(saved.content());
        assertEquals(INSTRUCTOR_PARTICIPANT, saved.instructorParticipantId());
        // 메모가 비어도 분석은 시작된다.
        assertEquals(List.of(SESSION_ID), pipelineJobPort.enqueuedSessionIds);
    }

    @Test
    void yieldsToTheAutomaticFinalizationThatWonTheTransition() {
        givenDraft();
        noteRepository.stealFinalizationBeforeNextTransition = true;
        noteRepository.stolenFinalizedAt = EARLIER;

        FinalizeNoteResult result = service.finalizeNote(command());

        assertEquals(NoteStatus.FINALIZED, result.status());
        // 확정은 이미 한 번 일어났으므로 성공이지만, 후속 작업을 두 번 하지 않도록 false 로 알린다.
        assertFalse(result.finalizedNow());
        // 이긴 쪽이 기록한 시각을 응답한다. 이 요청의 시계값(NOW)을 확정 시각인 것처럼 내보내면 안 된다.
        assertEquals(EARLIER, result.finalizedAt());
        // 작업은 이긴 쪽(자동 확정)이 남긴다. 이 경로에서 또 남기면 세션당 하나가 깨진다.
        assertTrue(pipelineJobPort.enqueuedSessionIds.isEmpty());
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
    void treatsASecondFinalizeOnADraftlessSessionAsSuccess() {
        // 초안 없는 세션에 확정이 동시에 두 번 왔고, 다른 요청이 먼저 확정 행을 만들었다.
        noteRepository.rowCreatedByAnotherRequestBeforeNextInsert =
                InstructorNote.finalizedWithoutDraft(SESSION_ID, INSTRUCTOR_PARTICIPANT, EARLIER);

        FinalizeNoteResult result = service.finalizeNote(command());

        // 확정은 멱등이다 — 뒤에 온 쪽에 409 를 주면 이미 확정된 수업을 두고 클라이언트가 재시도를 반복한다.
        assertEquals(NoteStatus.FINALIZED, result.status());
        assertEquals(EARLIER, result.finalizedAt());
        assertFalse(result.finalizedNow());
        // 작업은 먼저 확정한 쪽이 남겼다. 여기서 또 남기면 같은 수업의 분석이 두 번 돌아간다.
        assertTrue(pipelineJobPort.enqueuedSessionIds.isEmpty());
    }

    @Test
    void finalizesTheDraftThatAnotherRequestCreatedAtTheSameMoment() {
        // 확정을 누른 순간 첫 자동 저장이 초안 행을 만들었다. 그 행을 확정해야 한다 — 확정된 척하면 안 된다.
        InstructorNote draft = InstructorNote.open(SESSION_ID, INSTRUCTOR_PARTICIPANT);
        draft.saveDraft("자동 저장이 만든 초안", EARLIER);
        noteRepository.rowCreatedByAnotherRequestBeforeNextInsert = draft;

        FinalizeNoteResult result = service.finalizeNote(command());

        assertEquals(NoteStatus.FINALIZED, result.status());
        assertTrue(result.finalizedNow());
        assertTrue(noteRepository.findBySessionId(SESSION_ID).orElseThrow().isFinalized());
        // 확정한 쪽이 작업을 남긴다 — 초안이 이 요청보다 먼저 만들어졌더라도 마찬가지다.
        assertEquals(List.of(SESSION_ID), pipelineJobPort.enqueuedSessionIds);
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
