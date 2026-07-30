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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.TokenProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 강사 사후 메모 엔드포인트의 전 구간 검증. 로컬 MySQL/Redis가 떠 있어야 통과한다.
 *
 * <p>본문 규칙의 경우 분해는 {@code InstructorNoteTest} 가 전수로 다룬다. 여기서는 실제 스키마와 인증을 거친 계약과 상태 코드만 본다.
 */
@SpringBootTest
class InstructorNoteApiTest {

    private static final long INSTRUCTOR_ID = 9_300_810L;
    private static final long STUDENT_ID = 9_300_811L;
    private static final long SESSION_ID = 9_300_812L;
    private static final long INSTRUCTOR_PARTICIPANT_ID = 9_300_813L;
    private static final long STUDENT_PARTICIPANT_ID = 9_300_814L;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    private Instant now;

    /** Hibernate가 UTC로 저장(jdbc.time_zone=UTC)하므로, 직접 INSERT 할 때도 UTC 기준 시각을 넣는다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        cleanUpRows();
        insertMember(INSTRUCTOR_ID, "사후 메모 테스트 강사");
        insertMember(STUDENT_ID, "사후 메모 테스트 학생");
        insertEndedSession();
        insertParticipant(INSTRUCTOR_PARTICIPANT_ID, INSTRUCTOR_ID, "INSTRUCTOR");
        insertParticipant(STUDENT_PARTICIPANT_ID, STUDENT_ID, "STUDENT");
    }

    @AfterEach
    void tearDown() {
        cleanUpRows();
    }

    @Test
    void savesTheDraftContent() throws Exception {
        saveDraft(INSTRUCTOR_ID, "재귀 종료 조건에서 절반이 헷갈려했다")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.noteId").isNumber())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.updatedAt").isString());

        assertEquals("재귀 종료 조건에서 절반이 헷갈려했다", contentOf());
    }

    @Test
    void replacesTheContentOnEverySave() throws Exception {
        saveDraft(INSTRUCTOR_ID, "첫 입력").andExpect(status().isOk());

        saveDraft(INSTRUCTOR_ID, "이어 쓴 입력").andExpect(status().isOk());

        assertEquals("이어 쓴 입력", contentOf());
        assertEquals(1, noteCount());
    }

    @Test
    void rejectsContentLongerThanTheMaximum() throws Exception {
        saveDraft(INSTRUCTOR_ID, "가".repeat(5_001)).andExpect(status().isBadRequest());
    }

    @Test
    void acceptsContentThatOnlyExceedsTheMaximumBeforeTrimming() throws Exception {
        // 길이는 다듬은 뒤로 잰다. 요청 쪽에서 원본 길이로 먼저 자르면 이 입력이 잘못 거절된다.
        saveDraft(INSTRUCTOR_ID, "가".repeat(5_000) + "   ").andExpect(status().isOk());

        assertEquals(5_000, contentOf().length());
    }

    @Test
    void rejectsAStudentOnBothEndpoints() throws Exception {
        saveDraft(STUDENT_ID, "학생 요청").andExpect(status().isForbidden());
        finalizeNote(STUDENT_ID).andExpect(status().isForbidden());
    }

    @Test
    void rejectsANoteWhileTheSessionIsStillLive() throws Exception {
        jdbcTemplate.update("UPDATE sessions SET status = 'LIVE' WHERE id = ?", SESSION_ID);

        saveDraft(INSTRUCTOR_ID, "진행 중 수업").andExpect(status().isConflict());
    }

    @Test
    void finalizesOnceAndThenRefusesFurtherEdits() throws Exception {
        saveDraft(INSTRUCTOR_ID, "확정 대상 본문").andExpect(status().isOk());

        finalizeNote(INSTRUCTOR_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FINALIZED"));
        // 중복 확정은 오류가 아니라 멱등 성공이다(FRD §16).
        finalizeNote(INSTRUCTOR_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FINALIZED"));
        // 확정 후에는 수정·재생성할 수 없다.
        saveDraft(INSTRUCTOR_ID, "확정 후 수정").andExpect(status().isConflict());

        assertEquals("확정 대상 본문", contentOf());
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM instructor_notes WHERE session_id = ? AND status = 'FINALIZED'"
                                + " AND finalized_at IS NOT NULL",
                        Integer.class,
                        SESSION_ID));
        // 확정을 두 번 눌러도 사후 처리 작업은 한 건이다(FRD §16 NOTE-004).
        assertEquals(1, queuedJobCount());
    }

    @Test
    void finalizesWithoutADraftWhenTheInstructorSkipsTheNote() throws Exception {
        finalizeNote(INSTRUCTOR_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FINALIZED"));

        assertEquals(1, noteCount());
        assertNull(contentOf());
        // 메모가 비어도 분석은 시작된다.
        assertEquals(1, queuedJobCount());
    }

    private int queuedJobCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pipeline_jobs WHERE session_id = ? AND status = 'QUEUED'",
                Integer.class,
                SESSION_ID);
    }

    private ResultActions saveDraft(long memberId, String content) throws Exception {
        return mockMvc.perform(put("/api/v1/sessions/{sessionId}/notes/draft", SESSION_ID)
                .header("Authorization", bearer(memberId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"%s\"}".formatted(content)));
    }

    private ResultActions finalizeNote(long memberId) throws Exception {
        return mockMvc.perform(post("/api/v1/sessions/{sessionId}/notes/finalize", SESSION_ID)
                .header("Authorization", bearer(memberId)));
    }

    private String bearer(long memberId) {
        return "Bearer "
                + tokenProvider.issueAccessToken(String.valueOf(memberId)).value();
    }

    private String contentOf() {
        return jdbcTemplate.queryForObject(
                "SELECT content FROM instructor_notes WHERE session_id = ?", String.class, SESSION_ID);
    }

    private int noteCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM instructor_notes WHERE session_id = ?", Integer.class, SESSION_ID);
    }

    private void insertMember(long id, String displayName) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                id,
                "google-" + id,
                id + "@example.com",
                displayName,
                utc(now),
                utc(now));
    }

    private void insertEndedSession() {
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'ENDED', 'NOT_STARTED', ?, ?, ?, ?)",
                SESSION_ID,
                INSTRUCTOR_ID,
                "사후 메모 테스트",
                "NOTE8100",
                utc(now.minusSeconds(3_600)),
                utc(now.minusSeconds(1_800)),
                utc(now),
                utc(now));
    }

    private void insertParticipant(long participantId, long memberId, String role) {
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                participantId,
                SESSION_ID,
                memberId,
                role,
                utc(now.minusSeconds(3_600)),
                utc(now.minusSeconds(1_800)),
                utc(now),
                utc(now));
    }

    private void cleanUpRows() {
        jdbcTemplate.update("DELETE FROM pipeline_jobs WHERE session_id = ?", SESSION_ID);
        jdbcTemplate.update("DELETE FROM instructor_notes WHERE session_id = ?", SESSION_ID);
        jdbcTemplate.update("DELETE FROM session_participants WHERE session_id = ?", SESSION_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", SESSION_ID);
    }
}
