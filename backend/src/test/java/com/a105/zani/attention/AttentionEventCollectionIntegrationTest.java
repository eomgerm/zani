package com.a105.zani.attention;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

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

/** 실제 세션 참가자로 판정 이벤트를 보내는 전 구간 검증. 컨트롤러 테스트가 다루지 못하는 성공 경로(멱등·Redis 반영·종료 세션)를 본다. 로컬 MySQL/Redis가 떠 있어야 통과한다. */
@SpringBootTest
class AttentionEventCollectionIntegrationTest {

    private static final long INSTRUCTOR_ID = 9_100_910L;
    private static final long STUDENT_ID = 9_100_911L;
    private static final long SESSION_ID = 9_100_912L;
    private static final long PARTICIPANT_ID = 9_100_913L;
    private static final long INSTRUCTOR_PARTICIPANT_ID = 9_100_914L;
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private static final String STATE_KEY = "attention:" + SESSION_ID + ":state:" + PARTICIPANT_ID;

    private static String significantKey(AttentionState state) {
        return "attention:" + SESSION_ID + ":significant:" + state.name() + ":" + PARTICIPANT_ID;
    }

    private static String eventKey(String clientEventId) {
        return "attention:" + SESSION_ID + ":" + PARTICIPANT_ID + ":event:" + clientEventId;
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
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        insertMember(INSTRUCTOR_ID, "판정 수집 테스트 강사");
        insertMember(STUDENT_ID, "판정 수집 테스트 학생");
        insertLiveSession();
        insertStudentParticipant();
        insertInstructorParticipant();
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(STATE_KEY);
        for (AttentionState state : AttentionState.values()) {
            redisTemplate.delete(significantKey(state));
        }
        redisTemplate.delete(eventKey("integration-1"));
        redisTemplate.delete(eventKey("integration-2"));
        jdbcTemplate.update("DELETE FROM session_participants WHERE id = ?", PARTICIPANT_ID);
        jdbcTemplate.update("DELETE FROM session_participants WHERE id = ?", INSTRUCTOR_PARTICIPANT_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", SESSION_ID);
    }

    @Test
    void storesTheJudgementAsTheParticipantCurrentStateForTheAggregationToRead() throws Exception {
        postEvent("CONFUSED", "integration-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.duplicate").value(false));

        String stored = redisTemplate.opsForValue().get(STATE_KEY);
        assertNotNull(stored);
        assertTrue(stored.startsWith("CONFUSED|0.92|"), "unexpected stored state: " + stored);
        Long ttl = redisTemplate.getExpire(STATE_KEY);
        assertNotNull(ttl);
        // 판정 주기는 10초다. TTL이 그보다 짧으면 다음 판정 전에 상태가 사라진다.
        assertTrue(ttl > 10, "current state TTL should outlive one judging window, was " + ttl);
    }

    @Test
    void feedsTheCoachingTriggerWindowWhenTheJudgementIsSignificant() throws Exception {
        postEvent("CONFUSED", "integration-1").andExpect(status().isOk());

        assertEquals("1", redisTemplate.opsForValue().get(significantKey(AttentionState.CONFUSED)));
        Long ttl = redisTemplate.getExpire(significantKey(AttentionState.CONFUSED));
        assertNotNull(ttl);
        assertTrue(ttl > 240, "trigger window should hold about five minutes, was " + ttl);
    }

    @Test
    void leavesTheTriggerWindowUntouchedForAStudentWhoIsFollowingAlong() throws Exception {
        postEvent("GOOD", "integration-1").andExpect(status().isOk());

        assertTrue(startsWithState("GOOD"));
        assertNull(redisTemplate.opsForValue().get(significantKey(AttentionState.GOOD)));
    }

    @Test
    void treatsARetryWithTheSameClientEventIdAsAlreadyRecorded() throws Exception {
        postEvent("CONFUSED", "integration-1").andExpect(status().isOk());

        postEvent("GOOD", "integration-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(false))
                .andExpect(jsonPath("$.data.duplicate").value(true));

        // 재시도가 상태를 덮어쓰면 안 된다. 첫 판정이 그대로 남아 있어야 한다.
        assertTrue(startsWithState("CONFUSED"));
    }

    @Test
    void acceptsTheFollowingJudgementUnderItsOwnClientEventId() throws Exception {
        postEvent("CONFUSED", "integration-1").andExpect(status().isOk());

        postEvent("GOOD", "integration-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true));

        assertTrue(startsWithState("GOOD"));
    }

    @Test
    void tellsTheClientToStopSendingOnceTheSessionEnded() throws Exception {
        jdbcTemplate.update("UPDATE sessions SET status = 'ENDED' WHERE id = ?", SESSION_ID);

        postEvent("CONFUSED", "integration-1").andExpect(status().isConflict());

        assertNull(redisTemplate.opsForValue().get(STATE_KEY));
    }

    @Test
    void refusesAJudgementSentByTheInstructor() throws Exception {
        String body = """
                {"type":"CONFUSED","startedAt":"2026-07-28T09:00:00Z","endedAt":"2026-07-28T09:00:10Z",                "durationSec":10,"signalQuality":0.92,"clientEventId":"integration-1"}""";

        mockMvc.perform(post("/api/v1/sessions/{sessionId}/attention-events", SESSION_ID)
                        .header(
                                "Authorization",
                                "Bearer "
                                        + tokenProvider
                                                .issueAccessToken(String.valueOf(INSTRUCTOR_ID))
                                                .value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ATTENTION_EVENT_001"));

        assertNull(redisTemplate.opsForValue().get("attention:" + SESSION_ID + ":state:" + INSTRUCTOR_PARTICIPANT_ID));
    }

    /** 저장된 현재 상태가 이 상태로 시작하는지. 값은 {@code 상태|비율|시각} 형식이다. */
    private boolean startsWithState(String state) {
        String stored = redisTemplate.opsForValue().get(STATE_KEY);
        return stored != null && stored.startsWith(state + "|");
    }

    private ResultActions postEvent(String type, String clientEventId) throws Exception {
        String body = """
                {"type":"%s","startedAt":"2026-07-28T09:00:00Z","endedAt":"2026-07-28T09:00:10Z",\
                "durationSec":10,"signalQuality":0.92,"clientEventId":"%s"}""".formatted(type, clientEventId);
        return mockMvc.perform(post("/api/v1/sessions/{sessionId}/attention-events", SESSION_ID)
                .header("Authorization", "Bearer " + studentToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String studentToken() {
        return tokenProvider.issueAccessToken(String.valueOf(STUDENT_ID)).value();
    }

    private void insertMember(long id, String displayName) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                id,
                "google-" + id,
                id + "@example.com",
                displayName,
                utc(NOW),
                utc(NOW));
    }

    private void insertLiveSession() {
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", SESSION_ID);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'LIVE', 'NOT_STARTED', ?, ?, ?)",
                SESSION_ID,
                INSTRUCTOR_ID,
                "판정 수집 테스트",
                "ATTEN910",
                utc(NOW),
                utc(NOW),
                utc(NOW));
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
                utc(NOW),
                utc(NOW),
                utc(NOW),
                utc(NOW));
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
                utc(NOW),
                utc(NOW),
                utc(NOW),
                utc(NOW));
    }
}
