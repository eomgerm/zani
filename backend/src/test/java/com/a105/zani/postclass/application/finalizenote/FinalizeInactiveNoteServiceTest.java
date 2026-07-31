package com.a105.zani.postclass.application.finalizenote;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.application.InMemoryInstructorNoteRepository;
import com.a105.zani.postclass.application.InMemoryPipelineJobPort;
import com.a105.zani.postclass.application.exception.PipelineJobUnavailableException;
import com.a105.zani.postclass.domain.model.InstructorNote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalizeInactiveNoteServiceTest {

    private static final long SESSION_ID = 500L;
    private static final long INSTRUCTOR_PARTICIPANT = 7L;
    private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");
    /** 스윕이 대상을 고른 기준 시각. */
    private static final Instant EDITED_BEFORE = NOW.minus(InstructorNote.INACTIVITY_WINDOW);

    private final InMemoryInstructorNoteRepository noteRepository = new InMemoryInstructorNoteRepository();
    private final InMemoryPipelineJobPort pipelineJobPort = new InMemoryPipelineJobPort();

    private final FinalizeInactiveNoteService service =
            new FinalizeInactiveNoteService(noteRepository, pipelineJobPort, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void finalizesTheDraftAndQueuesThePostClassJob() {
        givenDraftEditedAt(EDITED_BEFORE.minusSeconds(60));

        assertTrue(service.finalizeInactiveNote(SESSION_ID, EDITED_BEFORE));

        assertTrue(noteRepository.findBySessionId(SESSION_ID).orElseThrow().isFinalized());
        assertEquals(List.of(SESSION_ID), pipelineJobPort.enqueuedSessionIds);
    }

    @Test
    void queuesNothingWhenTheManualFinalizeWonTheTransition() {
        givenDraftEditedAt(EDITED_BEFORE.minusSeconds(60));
        noteRepository.stealFinalizationBeforeNextTransition = true;

        assertFalse(service.finalizeInactiveNote(SESSION_ID, EDITED_BEFORE));

        // 이긴 쪽이 작업을 남긴다. 여기서 또 남기면 세션당 하나가 깨진다.
        assertTrue(pipelineJobPort.enqueuedSessionIds.isEmpty());
    }

    @Test
    void queuesNothingWhenTheNoteIsAlreadyFinalized() {
        noteRepository.save(InstructorNote.finalizedWithoutDraft(SESSION_ID, INSTRUCTOR_PARTICIPANT, NOW));

        assertFalse(service.finalizeInactiveNote(SESSION_ID, EDITED_BEFORE));
        assertTrue(pipelineJobPort.enqueuedSessionIds.isEmpty());
    }

    @Test
    void queuesNothingWhenTheInstructorTypedAfterTheNoteWasPicked() {
        // 스윕이 고른 뒤 강사가 다시 입력했다. 그 입력이 타이머를 초기화하므로(NOTE-002) 확정도 작업도 없어야 한다.
        givenDraftEditedAt(EDITED_BEFORE.plusSeconds(1));

        assertFalse(service.finalizeInactiveNote(SESSION_ID, EDITED_BEFORE));

        assertFalse(noteRepository.findBySessionId(SESSION_ID).orElseThrow().isFinalized());
        assertTrue(pipelineJobPort.enqueuedSessionIds.isEmpty());
    }

    @Test
    void failsTheWholeFinalizationWhenTheJobStoreIsDown() {
        givenDraftEditedAt(EDITED_BEFORE.minusSeconds(60));
        pipelineJobPort.failEnqueue = true;

        // 확정만 남고 작업이 없으면 그 수업의 분석은 영원히 시작되지 않는다. 트랜잭션째로 되돌려 다음 스윕이 다시 시도하게 한다.
        assertThrows(
                PipelineJobUnavailableException.class, () -> service.finalizeInactiveNote(SESSION_ID, EDITED_BEFORE));
    }

    private void givenDraftEditedAt(Instant editedAt) {
        InstructorNote note = InstructorNote.open(SESSION_ID, INSTRUCTOR_PARTICIPANT);
        note.saveDraft("초안 본문", editedAt);
        noteRepository.save(note);
    }
}
