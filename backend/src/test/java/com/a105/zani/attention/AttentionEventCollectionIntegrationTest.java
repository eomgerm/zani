package com.a105.zani.attention;

import java.time.Duration;
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

/** 실제 세션 학생으로 검출기 관측을 보내는 전 구간 검증. DB 기록과 Redis 파생 상태를 함께 본다. 로컬 MySQL/Redis가 떠 있어야 통과한다. */
@SpringBootTest
class AttentionEventCollectionIntegrationTest {

    private static final long INSTRUCTOR_ID = 9_100_910L;
    private static final long STUDENT_ID = 9_100_911L;
    private static final long SESSION_ID = 9_100_912L;
    private static final long PARTICIPANT_ID = 9_100_913L;
    private static final long INSTRUCTOR_PARTICIPANT_ID = 9_100_914L;
    private static final String SCHEMA = "mediapipe_98_v1";
    private static final String ENGINE = "e0g-1";

    private static final String STATE_KEY = "attention:" + SESSION_ID + ":state:" + PARTICIPANT_ID;

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

    /** 시각은 테스트마다 새로 잡는다. 고정 시각을 쓰면 3시간 뒤부터 만료 스케줄러가 세션을 끝낸다. */
    private Instant now;

    private Instant sessionStartedAt;

    /** 10초씩 앞으로 나아가는 창 번호. */
    private int window;

    /** Hibernate가 UTC로 저장(jdbc.time_zone=UTC)하므로, 직접 INSERT 할 때도 UTC 기준 시각을 넣는다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        sessionStartedAt = now.minusSeconds(300);
        window = 0;
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        insertMember(INSTRUCTOR_ID, "판정 수집 테스트 강사");
        insertMember(STUDENT_ID, "판정 수집 테스트 학생");
        insertLiveSession();
        insertStudentParticipant();
        insertInstructorParticipant();
        clearState();
    }

    @AfterEach
    void cleanUp() {
        clearState();
        jdbcTemplate.update("DELETE FROM attention_events WHERE session_id = ?", SESSION_ID);
        jdbcTemplate.update("DELETE FROM session_participants WHERE id = ?", PARTICIPANT_ID);
        jdbcTemplate.update("DELETE FROM session_participants WHERE id = ?", INSTRUCTOR_PARTICIPANT_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", SESSION_ID);
    }

    @Test
    void keepsEveryObservationAsEvidenceWithItsVersionsAndWindow() throws Exception {
        observe("BARELY_ENGAGED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.duplicate").value(false));

        Map<String, Object> row = onlyEventRow();
        assertEquals("BARELY_ENGAGED", row.get("detector_outcome"));
        assertEquals(2, ((Number) row.get("attention_score")).intValue());
        assertEquals(Boolean.TRUE, row.get("low_engagement"));
        assertEquals(SCHEMA, row.get("feature_schema_version"));
        assertEquals(ENGINE, row.get("engine_version"));
        assertNotNull(row.get("window_started_offset_ms"));

        // JPA 를 거치지 않아 감사 컬럼도 직접 넣는다. NOW(6) 을 쓰면 이 행만 다른 테이블보다 9시간 앞선다.
        Duration skew = Duration.between(utc(now), (LocalDateTime) row.get("created_at"));
        assertTrue(skew.abs().toMinutes() < 1, "created_at 이 UTC 가 아닙니다: " + row.get("created_at"));
    }

    @Test
    void derivesGoodFromAnEngagedObservation() throws Exception {
        observe("ENGAGED").andExpect(status().isOk());

        String stored = redisTemplate.opsForValue().get(STATE_KEY);
        assertNotNull(stored);
        assertTrue(stored.startsWith("GOOD|"), "unexpected stored state: " + stored);
        assertNull(redisTemplate.opsForValue().get(significantKey(AttentionState.GOOD)));
    }

    @Test
    void doesNotCountASingleUnmeasurableWindowAgainstTheStudent() throws Exception {
        observe("UNMEASURABLE").andExpect(status().isOk());
        observe("UNMEASURABLE").andExpect(status().isOk());

        // 자세를 고쳐 앉기만 해도 한 창이 UNMEASURABLE 이 된다. 3연속이어야 학생 상태가 된다.
        assertNull(redisTemplate.opsForValue().get(significantKey(AttentionState.UNMEASURABLE)));
    }

    @Test
    void confirmsUnmeasurableOnTheThirdConsecutiveWindow() throws Exception {
        observe("UNMEASURABLE").andExpect(status().isOk());
        observe("UNMEASURABLE").andExpect(status().isOk());
        observe("UNMEASURABLE").andExpect(status().isOk());

        assertEquals("1", redisTemplate.opsForValue().get(significantKey(AttentionState.UNMEASURABLE)));
    }

    @Test
    void acceptsACameraOffObservationWithoutAWindowAndKeepsItOutOfTheNumerator() throws Exception {
        observeImmediate("CAMERA_OFF").andExpect(status().isOk());

        Map<String, Object> row = onlyEventRow();
        assertEquals("CAMERA_OFF", row.get("detector_outcome"));
        assertNull(row.get("attention_score"));
        assertNull(row.get("window_started_offset_ms"));
        // 카메라를 끈 학생을 문제 있는 학생으로 세면 사실상 카메라를 강제하는 셈이다.
        assertNull(redisTemplate.opsForValue().get(significantKey(AttentionState.CAMERA_OFF)));
    }

    @Test
    void storesDetectorUnavailableSoTheServerCanTellItApartFromSilence() throws Exception {
        observeImmediate("DETECTOR_UNAVAILABLE").andExpect(status().isOk());

        assertEquals("DETECTOR_UNAVAILABLE", onlyEventRow().get("detector_outcome"));
        assertNull(redisTemplate.opsForValue().get(STATE_KEY));
    }

    @Test
    void refusesAWindowedOutcomeThatCarriesNoWindowStart() throws Exception {
        send("{\"outcome\":\"ENGAGED\",\"lowEngagement\":false,\"observedAt\":\"" + observedAt(1)
                        + "\",\"signalQuality\":0.92"
                        + ",\"featureSchemaVersion\":\"" + SCHEMA + "\",\"engineVersion\":\"" + ENGINE
                        + "\",\"clientEventId\":\"no-window\"}")
                .andExpect(status().isBadRequest());

        assertTrue(eventRows().isEmpty());
    }

    @Test
    void refusesAnEngagementLevelThatCarriesNoLowEngagementFlag() throws Exception {
        // 단계값만으로는 §3.3 의 확률 합 판단을 되짚을 수 없다.
        send("{\"outcome\":\"ENGAGED\",\"windowStartedAt\":\"" + windowStartedAt(1) + "\",\"observedAt\":\""
                        + observedAt(1) + "\",\"signalQuality\":0.92,\"featureSchemaVersion\":\"" + SCHEMA
                        + "\",\"engineVersion\":\"" + ENGINE + "\",\"clientEventId\":\"no-flag\"}")
                .andExpect(status().isBadRequest());

        assertTrue(eventRows().isEmpty());
    }

    @Test
    void refusesAnObservationFromAnUnsupportedFeatureSchema() throws Exception {
        send("{\"outcome\":\"ENGAGED\",\"lowEngagement\":false,\"windowStartedAt\":\"" + windowStartedAt(1)
                        + "\",\"observedAt\":\"" + observedAt(1)
                        + "\",\"signalQuality\":0.92,\"featureSchemaVersion\":\"mediapipe_64_v0\""
                        + ",\"engineVersion\":\"" + ENGINE + "\",\"clientEventId\":\"other-schema\"}")
                .andExpect(status().isBadRequest());

        assertTrue(eventRows().isEmpty());
    }

    @Test
    void refusesAnObservationCarryingProbabilitiesTheContractDoesNotAccept() throws Exception {
        // 원본 영상을 보관하지 않아 확률은 모델 개선에 쓸 수 없다. 받지 않는다.
        send(bodyFor("ENGAGED", "\"probabilities\":[0.1,0.2,0.6,0.1]", 1, "with-probabilities"))
                .andExpect(status().isBadRequest());

        assertTrue(eventRows().isEmpty());
    }

    @Test
    void treatsARetryWithTheSameClientEventIdAsAlreadyRecorded() throws Exception {
        String body = bodyFor("ENGAGED", null, 1, "repeat-1");
        send(body).andExpect(status().isOk());

        send(body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicate").value(true));

        assertEquals(1, eventRows().size());
    }

    @Test
    void tellsTheClientToStopSendingOnceTheSessionEnded() throws Exception {
        jdbcTemplate.update("UPDATE sessions SET status = 'ENDED' WHERE id = ?", SESSION_ID);

        observe("ENGAGED").andExpect(status().isConflict());

        assertTrue(eventRows().isEmpty());
    }

    @Test
    void refusesAnObservationSentByTheInstructor() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/{sessionId}/attention-events", SESSION_ID)
                        .header("Authorization", "Bearer " + token(INSTRUCTOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyFor("ENGAGED", null, 1, "instructor-1")))
                .andExpect(status().isForbidden());

        assertTrue(eventRows().isEmpty());
    }

    private ResultActions observe(String outcome) throws Exception {
        window++;
        return send(bodyFor(outcome, null, window, "event-" + window));
    }

    private ResultActions observeImmediate(String outcome) throws Exception {
        window++;
        return send("{\"outcome\":\"" + outcome + "\",\"observedAt\":\"" + observedAt(window)
                + "\",\"featureSchemaVersion\":\"" + SCHEMA + "\",\"engineVersion\":\"" + ENGINE
                + "\",\"clientEventId\":\"event-" + window + "\"}");
    }

    private String bodyFor(String outcome, String extraField, int windowIndex, String clientEventId) {
        String extra = extraField == null ? "" : "," + extraField;
        return "{\"outcome\":\"" + outcome + "\",\"lowEngagement\":" + lowEngagement(outcome)
                + ",\"windowStartedAt\":\"" + windowStartedAt(windowIndex)
                + "\",\"observedAt\":\"" + observedAt(windowIndex) + "\",\"signalQuality\":0.92"
                + ",\"featureSchemaVersion\":\"" + SCHEMA + "\",\"engineVersion\":\"" + ENGINE
                + "\",\"clientEventId\":\"" + clientEventId + "\"" + extra + "}";
    }

    /** 4단계 출력에는 저참여 여부가 필요하다. 1·2단계를 저참여로 보내 서버 카운터를 굴린다. */
    private boolean lowEngagement(String outcome) {
        return outcome.equals("NOT_ENGAGED") || outcome.equals("BARELY_ENGAGED");
    }

    private String windowStartedAt(int windowIndex) {
        return sessionStartedAt.plusSeconds(10L * (windowIndex - 1)).toString();
    }

    private String observedAt(int windowIndex) {
        return sessionStartedAt.plusSeconds(10L * windowIndex).toString();
    }

    private ResultActions send(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/sessions/{sessionId}/attention-events", SESSION_ID)
                .header("Authorization", "Bearer " + token(STUDENT_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String token(long memberId) {
        return tokenProvider.issueAccessToken(String.valueOf(memberId)).value();
    }

    private List<Map<String, Object>> eventRows() {
        return jdbcTemplate.queryForList(
                "SELECT * FROM attention_events WHERE session_id = ? ORDER BY occurred_offset_ms", SESSION_ID);
    }

    private Map<String, Object> onlyEventRow() {
        List<Map<String, Object>> rows = eventRows();
        assertEquals(1, rows.size(), "expected exactly one observation row");
        return rows.get(0);
    }

    private void clearState() {
        redisTemplate.delete(STATE_KEY);
        redisTemplate.delete("attention:" + SESSION_ID + ":applied:" + PARTICIPANT_ID);
        redisTemplate.delete("attention:" + SESSION_ID + ":run:low:" + PARTICIPANT_ID);
        redisTemplate.delete("attention:" + SESSION_ID + ":run:unmeasurable:" + PARTICIPANT_ID);
        for (AttentionState state : AttentionState.values()) {
            redisTemplate.delete(significantKey(state));
        }
        for (String id : List.of(
                "event-1",
                "event-2",
                "event-3",
                "repeat-1",
                "no-window",
                "with-probabilities",
                "no-flag",
                "other-schema")) {
            redisTemplate.delete("attention:" + SESSION_ID + ":" + PARTICIPANT_ID + ":event:" + id);
        }
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
                "판정 수집 테스트",
                "ATTEN910",
                utc(sessionStartedAt),
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
}
