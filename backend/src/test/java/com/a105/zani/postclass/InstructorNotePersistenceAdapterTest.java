package com.a105.zani.postclass;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.a105.zani.postclass.domain.exception.ConcurrentNoteOpenException;
import com.a105.zani.postclass.domain.model.InstructorNote;
import com.a105.zani.postclass.domain.repository.InstructorNoteRepository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 메모 첫 저장이 제약 위반을 어떤 오류로 옮기는지 고정한다. 로컬 MySQL 이 떠 있어야 통과한다.
 *
 * <p>같은 세션의 중복(UNIQUE)만 "다른 요청이 먼저 열었다"(409)이고, 나머지 제약 위반은 그대로 올라가야 한다. 잡는 범위를 넓히면 재시도해도 절대 풀리지 않는 결함이 재시도 가능한 충돌로
 * 위장되는데, 이 구분은 실제 DB 없이는 검증할 수 없다.
 */
@SpringBootTest
class InstructorNotePersistenceAdapterTest {

    private static final long MEMBER_ID = 9_301_010L;
    private static final long SESSION_ID = 9_301_011L;
    private static final long PARTICIPANT_ID = 9_301_012L;
    private static final long MISSING_PARTICIPANT_ID = 9_301_099L;

    @Autowired
    private InstructorNoteRepository instructorNoteRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        cleanUpRows();
        insertMember();
        insertEndedSession();
        insertParticipant();
    }

    @AfterEach
    void tearDown() {
        cleanUpRows();
    }

    @Test
    void reportsASecondOpenOnTheSameSessionAsAConflict() {
        instructorNoteRepository.save(draft(PARTICIPANT_ID));

        assertThrows(ConcurrentNoteOpenException.class, () -> instructorNoteRepository.save(draft(PARTICIPANT_ID)));
    }

    @Test
    void doesNotDisguiseAMissingParticipantAsAConflict() {
        // FK 위반은 재시도로 풀리지 않는 서버 결함이다. 409 로 내보내면 클라이언트가 영원히 재시도한다.
        // ConcurrentNoteOpenException 은 BusinessException 이라 이 타입이 아니다 — 그것이 올라오면 이 단정이 먼저 깨진다.
        assertThrows(
                DataIntegrityViolationException.class,
                () -> instructorNoteRepository.save(draft(MISSING_PARTICIPANT_ID)));

        assertFalse(noteExists());
    }

    private InstructorNote draft(long participantId) {
        InstructorNote note = InstructorNote.open(SESSION_ID, participantId);
        note.saveDraft("제약 검증용 본문", now);
        return note;
    }

    private boolean noteExists() {
        return jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM instructor_notes WHERE session_id = ?", Integer.class, SESSION_ID)
                > 0;
    }

    /** Hibernate가 UTC로 저장(jdbc.time_zone=UTC)하므로, 직접 INSERT 할 때도 UTC 기준 시각을 넣는다. */
    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private void insertMember() {
        jdbcTemplate.update(
                "INSERT IGNORE INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                MEMBER_ID,
                "google-" + MEMBER_ID,
                MEMBER_ID + "@example.com",
                "제약 검증 강사",
                utc(now),
                utc(now));
    }

    private void insertEndedSession() {
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'ENDED', 'NOT_STARTED', ?, ?, ?, ?)",
                SESSION_ID,
                MEMBER_ID,
                "제약 검증",
                "NOTE9301",
                utc(now.minusSeconds(3_600)),
                utc(now.minusSeconds(1_800)),
                utc(now),
                utc(now));
    }

    private void insertParticipant() {
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'INSTRUCTOR', ?, ?, ?, ?)",
                PARTICIPANT_ID,
                SESSION_ID,
                MEMBER_ID,
                utc(now.minusSeconds(3_600)),
                utc(now.minusSeconds(1_800)),
                utc(now),
                utc(now));
    }

    private void cleanUpRows() {
        jdbcTemplate.update("DELETE FROM instructor_notes WHERE session_id = ?", SESSION_ID);
        jdbcTemplate.update("DELETE FROM session_participants WHERE session_id = ?", SESSION_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", SESSION_ID);
    }
}
