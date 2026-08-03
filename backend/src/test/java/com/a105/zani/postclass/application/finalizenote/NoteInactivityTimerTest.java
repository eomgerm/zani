package com.a105.zani.postclass.application.finalizenote;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.application.InMemoryInstructorNoteRepository;
import com.a105.zani.postclass.application.InMemoryPipelineJobPort;
import com.a105.zani.postclass.application.savenotedraft.SaveNoteDraftCommand;
import com.a105.zani.postclass.application.savenotedraft.SaveNoteDraftService;
import com.a105.zani.postclass.domain.exception.NoteAlreadyFinalizedException;
import com.a105.zani.postclass.domain.model.InstructorNote;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantQuery;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantResult;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 30분 비활성 타이머를 시계를 옮겨 가며 검증한다(FRD §16 NOTE-002·NOTE-003).
 *
 * <p>초안 저장·스윕·확정을 같은 시계 위에 올려 한 수업의 시간선을 그대로 따라간다. 각 유스케이스를 따로 보면 "입력이 타이머를 되돌린다"는 규칙이 검증되지 않는다 — 되돌아간 기준으로 다시 30분을
 * 세는지가 이 테스트의 핵심이다.
 */
class NoteInactivityTimerTest {

    private static final long SESSION_ID = 500L;
    private static final long INSTRUCTOR_USER = 11L;
    private static final long INSTRUCTOR_PARTICIPANT = 7L;
    private static final Instant CLASS_ENDED_AT = Instant.parse("2026-07-30T09:00:00Z");
    private static final Instant CLASS_STARTED_AT = CLASS_ENDED_AT.minusSeconds(3600);
    private static final Duration WINDOW = InstructorNote.INACTIVITY_WINDOW;

    private final MutableClock clock = new MutableClock(CLASS_ENDED_AT);
    private final InMemoryInstructorNoteRepository noteRepository = new InMemoryInstructorNoteRepository();
    private final InMemoryPipelineJobPort pipelineJobPort = new InMemoryPipelineJobPort();

    private SaveNoteDraftService saveNoteDraft;
    private FinalizeDueNotesService sweep;

    @BeforeEach
    void setUp() {
        saveNoteDraft = new SaveNoteDraftService(new StubResolveEndedParticipant(), noteRepository, clock);
        sweep = new FinalizeDueNotesService(
                noteRepository, new FinalizeInactiveNoteService(noteRepository, pipelineJobPort, clock), clock);
    }

    @Test
    void finalizesWhenThirtyMinutesPassWithoutAnyInput() {
        saveDraft("첫 입력");

        advanceTo(WINDOW);

        assertEquals(1, sweep.finalizeDueNotes());
        assertTrue(noteRepository.findBySessionId(SESSION_ID).orElseThrow().isFinalized());
        assertEquals(List.of(SESSION_ID), pipelineJobPort.enqueuedSessionIds);
    }

    @Test
    void holdsTheDraftOneSecondShortOfTheWindow() {
        saveDraft("첫 입력");

        advanceTo(WINDOW.minusSeconds(1));

        assertEquals(0, sweep.finalizeDueNotes());
        assertTrue(pipelineJobPort.enqueuedSessionIds.isEmpty());
    }

    @Test
    void restartsTheWindowWhenTheInstructorTypesAtTwentyNineMinutes() {
        saveDraft("첫 입력");

        advanceTo(Duration.ofMinutes(29));
        saveDraft("29분에 이어 쓴 입력");

        // 첫 입력만 보면 이미 만료지만, 타이머는 마지막 입력에서 다시 시작한다.
        advanceTo(WINDOW);
        assertEquals(0, sweep.finalizeDueNotes());

        // 다시 시작한 30분이 지나면 확정된다.
        advanceTo(Duration.ofMinutes(29).plus(WINDOW));
        assertEquals(1, sweep.finalizeDueNotes());
        assertEquals(
                "29분에 이어 쓴 입력",
                noteRepository.findBySessionId(SESSION_ID).orElseThrow().content());
    }

    @Test
    void restartsTheWindowWhenTheInputLandsOneSecondBeforeExpiry() {
        saveDraft("첫 입력");

        advanceTo(WINDOW.minusSeconds(1));
        saveDraft("만료 직전 입력");

        // 만료 직전 입력도 타이머를 되돌린다. 이 순간의 스윕은 아무것도 확정하지 않는다.
        advanceTo(WINDOW);
        assertEquals(0, sweep.finalizeDueNotes());

        advanceTo(WINDOW.minusSeconds(1).plus(WINDOW));
        assertEquals(1, sweep.finalizeDueNotes());
        assertEquals(List.of(SESSION_ID), pipelineJobPort.enqueuedSessionIds);
    }

    @Test
    void queuesTheJobOnlyOnceEvenIfLaterSweepsRunAgain() {
        saveDraft("첫 입력");
        advanceTo(WINDOW);
        sweep.finalizeDueNotes();

        advanceTo(WINDOW.plus(WINDOW));
        assertEquals(0, sweep.finalizeDueNotes());

        assertEquals(List.of(SESSION_ID), pipelineJobPort.enqueuedSessionIds);
    }

    @Test
    void refusesToEditAfterTheAutomaticFinalization() {
        saveDraft("첫 입력");
        advanceTo(WINDOW);
        sweep.finalizeDueNotes();

        // 확정 후에는 수정·재생성할 수 없다(FRD §16). 클라이언트에는 409 로 나간다.
        advanceTo(WINDOW.plusSeconds(1));
        assertThrows(NoteAlreadyFinalizedException.class, () -> saveDraft("확정 후 수정"));
    }

    private void advanceTo(Duration sinceClassEnded) {
        clock.set(CLASS_ENDED_AT.plus(sinceClassEnded));
    }

    private void saveDraft(String content) {
        saveNoteDraft.save(new SaveNoteDraftCommand(SESSION_ID, INSTRUCTOR_USER, content));
    }

    private static final class StubResolveEndedParticipant implements ResolveEndedSessionParticipantUseCase {

        @Override
        public ResolveEndedSessionParticipantResult resolve(ResolveEndedSessionParticipantQuery query) {
            return new ResolveEndedSessionParticipantResult(
                    INSTRUCTOR_PARTICIPANT, SessionParticipantRole.INSTRUCTOR, CLASS_STARTED_AT, CLASS_ENDED_AT);
        }
    }

    /** 수업이 끝난 뒤의 시간선을 따라 옮기는 시계. 고정 시계로는 "입력이 타이머를 되돌린다"를 볼 수 없다. */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant next) {
            instant = next;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
