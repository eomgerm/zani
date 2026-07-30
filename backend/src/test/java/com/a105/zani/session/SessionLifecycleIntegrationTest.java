package com.a105.zani.session;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.TokenProvider;
import com.a105.zani.session.domain.model.Session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 생성 → 시작 → 입장 → 정원 초과까지를 실제 HTTP 로 통과시킨다. 정규화·상태 게이트·정원은 컨트롤러의 검증과 서비스의 잠금을 함께 지나야 의미가 있어 단위 테스트로는 대체되지 않는다.
 *
 * <p>로컬 MySQL·Redis 가 떠 있어야 한다. 세션 저장이 {@code REQUIRES_NEW} 라 테스트 트랜잭션으로 롤백되지 않으므로, 정리를 직접 한다. 자식 행
 * (session_participants·session_status_changes)을 먼저 지워야 FK 에 걸리지 않는다.
 *
 * <p>활성 잠금은 3시간 TTL 이라 setUp 에서도 지운다. 한 번 실패하고 남은 잠금이 그 뒤 모든 실행을 막기 때문이다.
 */
@SpringBootTest
class SessionLifecycleIntegrationTest {

    private static final long INSTRUCTOR_ID = 9_600_000L;
    /** 정원(강사 포함 30)을 넘겨보려면 학생이 정원만큼 필요하다. */
    private static final int STUDENT_POOL = Session.CAPACITY;

    private static final String ACTIVE_LOCK_KEY = "session:active-lock:" + INSTRUCTOR_ID;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private final List<Long> createdSessionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        redisTemplate.delete(ACTIVE_LOCK_KEY);
        insertMembers();
    }

    @AfterEach
    void tearDown() {
        for (Long sessionId : createdSessionIds) {
            jdbcTemplate.update("DELETE FROM session_participants WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM session_status_changes WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
        }
        createdSessionIds.clear();
        jdbcTemplate.update("DELETE FROM members WHERE id BETWEEN ? AND ?", INSTRUCTOR_ID, lastStudentId());
        redisTemplate.delete(ACTIVE_LOCK_KEY);
    }

    /**
     * 완료 조건 그대로: 생성 직후 강사가 토큰을 받고, 시작한 뒤 학생 29명이 들어오고, 31번째가 거절된다.
     *
     * <p>강사도 참가자 한 자리를 쓴다. 그래서 정원 30명은 강사 1 + 학생 29 이고, 그다음 학생이 31번째다.
     */
    @Test
    void createStartAndFillTheSessionUpToItsCapacity() throws Exception {
        JsonNode created = createSession();
        long sessionId = created.get("sessionId").asLong();
        // 생성 직후에는 아직 시작 전이다. 초대 코드는 있지만 학생이 들어올 수 없다.
        assertEquals("PREPARING", created.get("status").asText());
        assertTrue(created.get("expiresAt").isNull(), "시작 전에는 자동 종료 시각이 없다");

        // 강사는 준비 단계에서 방에 들어가 카메라·마이크를 맞춘다. 참가자 행이 없으면 여기서 403 이 났다.
        assertInstructorCanRequestAMediaToken(sessionId);

        String inviteCode = start(sessionId).get("inviteCode").asText();

        for (int i = 0; i < Session.CAPACITY - 1; i++) {
            join(inviteCode, studentId(i)).andExpect(status().isOk());
        }

        // 강사 1 + 학생 29 = 30. 그다음 학생이 31번째다.
        join(inviteCode, studentId(Session.CAPACITY - 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_APP_009"));

        assertEquals(Session.CAPACITY, participantCountOf(sessionId));
    }

    /** 사용자가 코드를 어떤 형태로 옮겨 적었는지에 결과가 달라지면 안 된다. 브라우저를 거치지 않는 호출도 서버가 맞춰준다. */
    @Test
    void acceptsHyphenatedAndLowercaseInviteCodes() throws Exception {
        long sessionId = createSession().get("sessionId").asLong();
        String inviteCode = start(sessionId).get("inviteCode").asText();
        String displayForm = inviteCode.substring(0, 4).toLowerCase() + "-"
                + inviteCode.substring(4).toLowerCase();

        join(displayForm, studentId(0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inviteCode").value(inviteCode));
    }

    @Test
    void rejectsCodesThatAreNotEightCharactersAfterNormalization() throws Exception {
        join("ABCD-EFG", studentId(0)).andExpect(status().isBadRequest());
    }

    /** 시작 전 수업의 코드로는 들어갈 수 없다. 학생이 빈 방에 먼저 들어오지 않게 하려는 것이다. */
    @Test
    void rejectsJoiningBeforeTheInstructorStartsTheSession() throws Exception {
        String inviteCode = createSession().get("inviteCode").asText();

        join(inviteCode, studentId(0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_APP_007"));
    }

    @Test
    void rejectsJoiningAnEndedSession() throws Exception {
        long sessionId = createSession().get("sessionId").asLong();
        String inviteCode = start(sessionId).get("inviteCode").asText();

        mockMvc.perform(post("/api/v1/sessions/{sessionId}/end", sessionId)
                        .header("Authorization", "Bearer " + tokenOf(INSTRUCTOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOTE_PENDING"));

        join(inviteCode, studentId(0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_APP_008"));
    }

    /** 시작은 멱등해야 한다. 두 번 눌러 시작 시각이 밀리면 수업이 예정보다 늦게 끝난다. */
    @Test
    void startingTwiceDoesNotPushTheExpiry() throws Exception {
        long sessionId = createSession().get("sessionId").asLong();

        JsonNode first = start(sessionId);
        JsonNode second = start(sessionId);

        assertTrue(first.get("started").asBoolean());
        assertEquals(false, second.get("started").asBoolean());
        assertEquals(first.get("expiresAt").asText(), second.get("expiresAt").asText());
    }

    /** 전이 이력이 없으면 수업이 끝난 뒤 언제 시작했는지가 남지 않는다. 세션 행은 현재 상태만 들고 있다. */
    @Test
    void recordsTheStartTransitionInTheStatusHistory() throws Exception {
        long sessionId = createSession().get("sessionId").asLong();

        start(sessionId);

        Long transitions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_status_changes"
                        + " WHERE session_id = ? AND from_status = 'PREPARING' AND to_status = 'LIVE'",
                Long.class,
                sessionId);
        assertEquals(1L, transitions);
    }

    private JsonNode createSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer " + tokenOf(INSTRUCTOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"정원 통합 테스트\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode data = dataOf(result);
        createdSessionIds.add(data.get("sessionId").asLong());
        return data;
    }

    private JsonNode start(long sessionId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/sessions/{sessionId}/start", sessionId)
                        .header("Authorization", "Bearer " + tokenOf(INSTRUCTOR_ID)))
                .andExpect(status().isOk())
                .andReturn();
        return dataOf(result);
    }

    private org.springframework.test.web.servlet.ResultActions join(String inviteCode, long studentId)
            throws Exception {
        return mockMvc.perform(post("/api/v1/sessions/join")
                .header("Authorization", "Bearer " + tokenOf(studentId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inviteCode\":\"" + inviteCode + "\"}"));
    }

    /**
     * 강사가 자기 방의 미디어 토큰을 받을 수 있는지.
     *
     * <p>200 을 못 박지 않는다. LiveKit 자격증명이 없는 환경에서는 503 이 정상이고, 이 완료 조건이 지키려는 건 "강사가 참가자로 등록돼 있다"는 사실이다. 참가자 행이 없으면 403 이
     * 나므로 그것만 배제한다.
     */
    private void assertInstructorCanRequestAMediaToken(long sessionId) throws Exception {
        int httpStatus = mockMvc.perform(post("/api/v1/sessions/{sessionId}/media-token", sessionId)
                        .header("Authorization", "Bearer " + tokenOf(INSTRUCTOR_ID)))
                .andReturn()
                .getResponse()
                .getStatus();

        assertNotEquals(403, httpStatus, "강사는 생성과 동시에 참가자로 등록돼야 한다");
        assertNotEquals(404, httpStatus, "생성한 세션을 찾을 수 있어야 한다");
        assertNotEquals(409, httpStatus, "준비 중인 세션에도 토큰이 나와야 한다");
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private long participantCountOf(long sessionId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_participants WHERE session_id = ?", Long.class, sessionId);
        return count == null ? 0 : count;
    }

    private String tokenOf(long userId) {
        return tokenProvider.issueAccessToken(String.valueOf(userId)).value();
    }

    private static long studentId(int index) {
        return INSTRUCTOR_ID + 1 + index;
    }

    private static long lastStudentId() {
        return studentId(STUDENT_POOL - 1);
    }

    /** session_participants.member_id 는 members 를 FK 로 참조하므로 회원을 먼저 시딩한다. */
    private void insertMembers() {
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {INSTRUCTOR_ID, "정원 테스트 강사", now});
        for (int i = 0; i < STUDENT_POOL; i++) {
            rows.add(new Object[] {studentId(i), "정원 테스트 학생 " + i, now});
        }
        jdbcTemplate.batchUpdate(
                "INSERT IGNORE INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                rows.stream()
                        .map(row ->
                                new Object[] {row[0], "google-" + row[0], row[0] + "@zani.local", row[1], row[2], row[2]
                                })
                        .toList());
    }
}
