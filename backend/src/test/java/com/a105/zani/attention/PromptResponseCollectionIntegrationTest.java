package com.a105.zani.attention;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.auth.application.port.TokenProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 세션 학생으로 프롬프트 응답을 보내는 전 구간 검증. DB 기록과 Redis 반영을 함께 본다. 로컬 MySQL/Redis가 떠 있어야 통과한다. */
@SpringBootTest
class PromptResponseCollectionIntegrationTest {

    private static final long INSTRUCTOR_ID = 9_100_930L;
    private static final long STUDENT_ID = 9_100_931L;
    private static final long SESSION_ID = 9_100_932L;
    private static final long PARTICIPANT_ID = 9_100_933L;
    private static final long INSTRUCTOR_PARTICIPANT_ID = 9_100_934L;
    private static final String PROMPT_ID = "prompt-integration-1";

    /** 시각은 테스트마다 새로 잡는다. 고정 시각을 쓰면 3시간 뒤부터 만료 스케줄러가 세션을 끝내고, 클래스 로드 시각에 묶어 두면 컨텍스트 기동이 길어질 때 프롬프트가 오래된 것으로 걸린다. */
    private Instant now;

    private Instant sessionStartedAt;
    private Instant shownAt;
    private Instant respondedAt;

    private static final String STATE_KEY = "attention:" + SESSION_ID + ":state:" + PARTICIPANT_ID;
    private static final String EXCLUDED_KEY = "attention:" + SESSION_ID + ":excluded:" + PARTICIPANT_ID;
    private static final String PROMPT_MARKER_KEY =
            "attention:" + SESSION_ID + ":" + PARTICIPANT_ID + ":event:prompt:" + PROMPT_ID;

    private static String significantKey(AttentionState state) {
        return "attention:" + SESSION_ID + ":significant:" + state.name() + ":" + PARTICIPANT_ID;
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private MockMvc mockMvc;

    /** Hibernate가 UTC로 저장(jdbc.time_zone=UTC)하므로, 직접 INSERT 할 때도 UTC 기준 시각을 넣는다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        sessionStartedAt = now.minusSeconds(120);
        shownAt = sessionStartedAt.plusSeconds(60);
        respondedAt = shownAt.plusSeconds(8);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        insertMember(INSTRUCTOR_ID, "프롬프트 응답 테스트 강사");
        insertMember(STUDENT_ID, "프롬프트 응답 테스트 학생");
        insertLiveSession();
        insertStudentParticipant();
        insertInstructorParticipant();
        // 앞선 실행이 비정상 종료로 남긴 키가 있으면 첫 요청이 중복으로 잡힌다. 시작할 때도 지운다.
        clearRedisKeys();
    }

    private void clearRedisKeys() {
        redisTemplate.delete(STATE_KEY);
        redisTemplate.delete(EXCLUDED_KEY);
        redisTemplate.delete(PROMPT_MARKER_KEY);
        for (AttentionState state : AttentionState.values()) {
            redisTemplate.delete(significantKey(state));
        }
    }

    @AfterEach
    void cleanUp() {
        clearRedisKeys();
        jdbcTemplate.update("DELETE FROM check_prompts WHERE session_id = ?", SESSION_ID);
        jdbcTemplate.update("DELETE FROM session_participants WHERE id = ?", PARTICIPANT_ID);
        jdbcTemplate.update("DELETE FROM session_participants WHERE id = ?", INSTRUCTOR_PARTICIPANT_ID);
        // 세션 상태 전이 이력을 먼저 지운다. 3시간 만료 스윕이 테스트 중에도 돌아(1분 주기)
        // 오래된 시작 시각을 가진 이 세션을 종료시키면서 이력 행을 남기므로, FK 때문에 세션을 지울 수 없다.
        jdbcTemplate.update("DELETE FROM session_status_changes WHERE session_id = ?", SESSION_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", SESSION_ID);
    }

    @Test
    void keepsTheAnswerAsEvidenceWithOffsetsMeasuredFromTheSessionStart() throws Exception {
        respond("UNDERSTANDING_CHECK", "CONFUSED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.duplicate").value(false));

        Map<String, Object> row = onlyPromptRow();
        assertEquals("UNDERSTANDING_CHECK", row.get("trigger_type"));
        assertEquals("RESPONDED", row.get("status"));
        assertEquals("CONFUSED", row.get("response"));
        assertEquals(60_000L, ((Number) row.get("shown_offset_ms")).longValue());
        assertEquals(68_000L, ((Number) row.get("responded_offset_ms")).longValue());
    }

    @Test
    void feedsTheAnswerStraightIntoTheCoachingAggregation() throws Exception {
        respond("UNDERSTANDING_CHECK", "CONFUSED").andExpect(status().isOk());

        String stored = redisTemplate.opsForValue().get(STATE_KEY);
        assertNotNull(stored);
        assertTrue(stored.startsWith("CONFUSED|"), "unexpected stored state: " + stored);
        assertEquals("1", redisTemplate.opsForValue().get(significantKey(AttentionState.CONFUSED)));
    }

    @Test
    void recordsSilenceAsANonResponseThatTimedOut() throws Exception {
        respond("UNDERSTANDING_CHECK", "NON_RESPONSE").andExpect(status().isOk());

        Map<String, Object> row = onlyPromptRow();
        assertEquals("TIMEOUT", row.get("status"));
        assertNull(row.get("responded_offset_ms"));
        assertEquals("1", redisTemplate.opsForValue().get(significantKey(AttentionState.NON_RESPONSE)));
    }

    @Test
    void keepsTheFirstAnswerWhenTheStudentAnswersTheSamePromptTwice() throws Exception {
        respond("UNDERSTANDING_CHECK", "CONFUSED").andExpect(status().isOk());

        respond("UNDERSTANDING_CHECK", "OK")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(false))
                .andExpect(jsonPath("$.data.duplicate").value(true));

        assertEquals("CONFUSED", onlyPromptRow().get("response"));
    }

    @Test
    void refusesTheRetiredCameraAnswerAndLeavesTheDenominatorAlone() throws Exception {
        respond("UNDERSTANDING_CHECK", "CAMERA_UNAVAILABLE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMM_400"));

        // 학생 답으로 분모에서 빼면 빠지는 쪽이 늘 유리해져 모두가 그 답을 고른다(§5.2). 이제 그 값 자체가 계약에 없다.
        assertNull(redisTemplate.opsForValue().get(EXCLUDED_KEY));
        assertTrue(promptRows().isEmpty());
    }

    @Test
    void refusesARetiredPromptKindThatTheBrowserNoLongerReports() throws Exception {
        // 자세 안내·카메라 안내는 브라우저 안에서 끝난다(티켓 81).
        respond("POSTURE_GUIDE", "CONFUSED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMM_400"));

        assertTrue(promptRows().isEmpty());
    }

    @Test
    void refusesAnAnswerSentByTheInstructor() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/{sessionId}/prompts/{promptId}/responses", SESSION_ID, PROMPT_ID)
                        .header("Authorization", "Bearer " + token(INSTRUCTOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("UNDERSTANDING_CHECK", "CONFUSED")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROMPT_RESPONSE_001"));

        assertTrue(promptRows().isEmpty());
    }

    private ResultActions respond(String kind, String answer) throws Exception {
        return mockMvc.perform(post("/api/v1/sessions/{sessionId}/prompts/{promptId}/responses", SESSION_ID, PROMPT_ID)
                .header("Authorization", "Bearer " + token(STUDENT_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(kind, answer)));
    }

    private String body(String kind, String answer) {
        return """
                {"kind":"%s","answer":"%s","shownAt":"%s","respondedAt":"%s"}""".formatted(kind, answer, shownAt, respondedAt);
    }

    private String token(long memberId) {
        return tokenProvider.issueAccessToken(String.valueOf(memberId)).value();
    }

    private List<Map<String, Object>> promptRows() {
        return jdbcTemplate.queryForList("SELECT * FROM check_prompts WHERE session_id = ?", SESSION_ID);
    }

    private Map<String, Object> onlyPromptRow() {
        List<Map<String, Object>> rows = promptRows();
        assertEquals(1, rows.size(), "expected exactly one prompt row");
        return rows.get(0);
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

    private void insertLiveSession() {
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", SESSION_ID);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'LIVE', 'NOT_STARTED', ?, ?, ?)",
                SESSION_ID,
                INSTRUCTOR_ID,
                "프롬프트 응답 테스트",
                "ATTEN930",
                utc(sessionStartedAt),
                utc(now),
                utc(now));
    }

    private void insertInstructorParticipant() {
        jdbcTemplate.update("DELETE FROM session_participants WHERE id = ?", INSTRUCTOR_PARTICIPANT_ID);
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'INSTRUCTOR', ?, ?, ?, ?)",
                INSTRUCTOR_PARTICIPANT_ID,
                SESSION_ID,
                INSTRUCTOR_ID,
                utc(now),
                utc(now),
                utc(now),
                utc(now));
    }

    private void insertStudentParticipant() {
        jdbcTemplate.update("DELETE FROM session_participants WHERE id = ?", PARTICIPANT_ID);
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'STUDENT', ?, ?, ?, ?)",
                PARTICIPANT_ID,
                SESSION_ID,
                STUDENT_ID,
                utc(now),
                utc(now),
                utc(now),
                utc(now));
    }
}
