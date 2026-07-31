package com.a105.zani.postclass;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.a105.zani.postclass.application.finalizenote.FinalizeDueNotesUseCase;
import com.a105.zani.postclass.domain.model.InstructorNote;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 30분 비활성 자동 확정을 실제 스키마에서 확인한다. 로컬 MySQL/Redis가 떠 있어야 통과한다.
 *
 * <p>경계값과 동시성은 티켓 92 가 fake clock 으로 전수로 다룬다. 여기서는 "만료 대상을 고르는 조회"와 "조건부 전환"이 실제 DB 에서 맞물리는지만 본다 — 시각 기준 판정이라 마지막 입력
 * 시각을 과거로 심어 재현한다.
 */
@SpringBootTest
class NoteInactivitySweepIntegrationTest {

    private static final long INSTRUCTOR_ID = 9_300_910L;
    private static final long SESSION_ID = 9_300_912L;
    private static final long INSTRUCTOR_PARTICIPANT_ID = 9_300_913L;
    private static final long IDLE_NOTE_ID = 9_300_920L;
    private static final long FRESH_NOTE_ID = 9_300_921L;
    private static final long FRESH_SESSION_ID = 9_300_922L;
    private static final long FRESH_PARTICIPANT_ID = 9_300_923L;

    @Autowired
    private FinalizeDueNotesUseCase finalizeDueNotesUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Instant now;

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        cleanUpRows();
        insertMember();
        insertEndedSession(SESSION_ID, "NOTE9120");
        insertEndedSession(FRESH_SESSION_ID, "NOTE9122");
        insertParticipant(INSTRUCTOR_PARTICIPANT_ID, SESSION_ID);
        insertParticipant(FRESH_PARTICIPANT_ID, FRESH_SESSION_ID);
    }

    @AfterEach
    void tearDown() {
        cleanUpRows();
    }

    @Test
    void finalizesTheIdleDraftAndLeavesTheFreshOneAlone() {
        // 만료: 마지막 입력이 30분 + 1분 전. 유효: 1분 전.
        insertDraft(
                IDLE_NOTE_ID, SESSION_ID, INSTRUCTOR_PARTICIPANT_ID, InstructorNote.INACTIVITY_WINDOW.plusMinutes(1));
        insertDraft(FRESH_NOTE_ID, FRESH_SESSION_ID, FRESH_PARTICIPANT_ID, Duration.ofMinutes(1));

        finalizeDueNotesUseCase.finalizeDueNotes();

        assertEquals("FINALIZED", statusOf(IDLE_NOTE_ID));
        assertEquals(1, finalizedAtCount(IDLE_NOTE_ID));
        assertEquals("DRAFT", statusOf(FRESH_NOTE_ID));
        // 확정이 곧 사후 처리의 시작점이다(FRD §16 NOTE-004). 아직 만료되지 않은 세션에는 작업이 없다.
        assertEquals(1, queuedJobCount(SESSION_ID));
        assertEquals(0, queuedJobCount(FRESH_SESSION_ID));
    }

    @Test
    void queuesTheJobOnlyOnceEvenIfTheSweepRunsAgain() {
        insertDraft(
                IDLE_NOTE_ID, SESSION_ID, INSTRUCTOR_PARTICIPANT_ID, InstructorNote.INACTIVITY_WINDOW.plusMinutes(1));

        finalizeDueNotesUseCase.finalizeDueNotes();
        finalizeDueNotesUseCase.finalizeDueNotes();

        // 두 번째 스윕은 이미 확정된 메모를 고르지 않는다. 골랐더라도 session_id UNIQUE 가 작업을 하나로 묶는다.
        assertEquals(1, queuedJobCount(SESSION_ID));
    }

    @Test
    void keepsTheDraftedContentWhenFinalizingAutomatically() {
        insertDraft(
                IDLE_NOTE_ID, SESSION_ID, INSTRUCTOR_PARTICIPANT_ID, InstructorNote.INACTIVITY_WINDOW.plusMinutes(1));

        finalizeDueNotesUseCase.finalizeDueNotes();

        assertEquals(
                "자동 확정 대상 본문",
                jdbcTemplate.queryForObject(
                        "SELECT content FROM instructor_notes WHERE id = ?", String.class, IDLE_NOTE_ID));
    }

    @Test
    void leavesAnAlreadyFinalizedNoteUntouched() {
        insertDraft(
                IDLE_NOTE_ID, SESSION_ID, INSTRUCTOR_PARTICIPANT_ID, InstructorNote.INACTIVITY_WINDOW.plusMinutes(1));
        jdbcTemplate.update(
                "UPDATE instructor_notes SET status = 'FINALIZED', finalized_at = ? WHERE id = ?",
                utc(now.minusSeconds(60)),
                IDLE_NOTE_ID);

        assertEquals(0, finalizeDueNotesUseCase.finalizeDueNotes());

        // 확정 시각이 스윕 시각으로 덮이지 않아야 한다 — 확정은 한 번만 일어난다.
        assertEquals(
                utc(now.minusSeconds(60)).withNano(0),
                jdbcTemplate
                        .queryForObject(
                                "SELECT finalized_at FROM instructor_notes WHERE id = ?",
                                LocalDateTime.class,
                                IDLE_NOTE_ID)
                        .withNano(0));
    }

    private String statusOf(long noteId) {
        return jdbcTemplate.queryForObject("SELECT status FROM instructor_notes WHERE id = ?", String.class, noteId);
    }

    private int queuedJobCount(long sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pipeline_jobs WHERE session_id = ? AND status = 'QUEUED'",
                Integer.class,
                sessionId);
    }

    private int finalizedAtCount(long noteId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM instructor_notes WHERE id = ? AND finalized_at IS NOT NULL",
                Integer.class,
                noteId);
    }

    private void insertDraft(long noteId, long sessionId, long participantId, Duration idleFor) {
        jdbcTemplate.update(
                "INSERT INTO instructor_notes (id, session_id, instructor_participant_id, content, status,"
                        + " last_edited_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, ?)",
                noteId,
                sessionId,
                participantId,
                "자동 확정 대상 본문",
                utc(now.minus(idleFor)),
                utc(now.minus(idleFor)),
                utc(now.minus(idleFor)));
    }

    private void insertMember() {
        jdbcTemplate.update(
                "INSERT IGNORE INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                INSTRUCTOR_ID,
                "google-" + INSTRUCTOR_ID,
                INSTRUCTOR_ID + "@example.com",
                "메모 만료 테스트 강사",
                utc(now),
                utc(now));
    }

    private void insertEndedSession(long sessionId, String inviteCode) {
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'ENDED', 'NOT_STARTED', ?, ?, ?, ?)",
                sessionId,
                INSTRUCTOR_ID,
                "메모 만료 테스트",
                inviteCode,
                utc(now.minusSeconds(7_200)),
                utc(now.minusSeconds(3_600)),
                utc(now),
                utc(now));
    }

    private void insertParticipant(long participantId, long sessionId) {
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'INSTRUCTOR', ?, ?, ?, ?)",
                participantId,
                sessionId,
                INSTRUCTOR_ID,
                utc(now.minusSeconds(7_200)),
                utc(now.minusSeconds(3_600)),
                utc(now),
                utc(now));
    }

    private void cleanUpRows() {
        jdbcTemplate.update("DELETE FROM pipeline_jobs WHERE session_id IN (?, ?)", SESSION_ID, FRESH_SESSION_ID);
        jdbcTemplate.update("DELETE FROM instructor_notes WHERE session_id IN (?, ?)", SESSION_ID, FRESH_SESSION_ID);
        jdbcTemplate.update(
                "DELETE FROM session_participants WHERE session_id IN (?, ?)", SESSION_ID, FRESH_SESSION_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE id IN (?, ?)", SESSION_ID, FRESH_SESSION_ID);
    }
}
