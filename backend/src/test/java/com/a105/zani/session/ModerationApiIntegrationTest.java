package com.a105.zani.session;

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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.TokenProvider;
import com.a105.zani.session.application.port.MediaModerationPort;
import com.a105.zani.session.application.port.MediaMuteChange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 강사 제어 엔드포인트의 전 구간 검증. 로컬 MySQL/Redis 가 떠 있어야 통과한다.
 *
 * <p><b>단위 테스트로는 이 파일이 보는 것을 볼 수 없다.</b> {@code MuteParticipantServiceTest} 는 유스케이스를 직접 부르므로 경로·인증·직렬화·오류 매핑을 지나지 않는다.
 * 63 에서 거절 통지가 브로커 설정 한 줄 때문에 조용히 사라졌던 것이 그 계층에서만 드러났던 종류다.
 *
 * <p>미디어 서버는 대체한다. LiveKit 을 띄우지 않고도 <b>권한 판정과 응답 계약</b>을 확인하는 것이 이 파일의 목적이고, 실제 트랙 조작은 어댑터 소관이다.
 */
@SpringBootTest
@Import(ModerationApiIntegrationTest.StubModerationConfig.class)
class ModerationApiIntegrationTest {

    private static final long INSTRUCTOR_ID = 9_800_910L;
    private static final long STUDENT_ID = 9_800_911L;
    private static final long OUTSIDER_ID = 9_800_912L;
    private static final long SESSION_ID = 9_800_913L;
    private static final long STUDENT_PARTICIPANT_ID = 9_800_914L;
    private static final long INSTRUCTOR_PARTICIPANT_ID = 9_800_915L;
    private static final long UNKNOWN_PARTICIPANT_ID = 9_800_999L;

    /** {@code invite_code} 는 CHAR(8) 이다. 세션 ID 를 잘라 쓰면 자릿수가 바뀔 때 깨진다. */
    private static final String INVITE_CODE = "MOD00066";

    /** 테스트가 결과를 갈아 끼우는 자리. LiveKit 없이 성공·이미 음소거·장애를 흉내 낸다. */
    static final ThreadLocal<MediaMuteChange> NEXT_CHANGE = ThreadLocal.withInitial(() -> MediaMuteChange.CHANGED);

    @TestConfiguration
    static class StubModerationConfig {

        @Bean
        @Primary
        MediaModerationPort stubMediaModerationPort() {
            return (sessionId, identity) -> NEXT_CHANGE.get();
        }
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private Instant now;

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @BeforeEach
    void setUp() {
        NEXT_CHANGE.set(MediaMuteChange.CHANGED);
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        insertMember(INSTRUCTOR_ID, "박강사");
        insertMember(STUDENT_ID, "김민수");
        insertMember(OUTSIDER_ID, "남의 수업 사람");
        insertLiveSession();
        insertParticipant(STUDENT_PARTICIPANT_ID, STUDENT_ID, "STUDENT");
        insertParticipant(INSTRUCTOR_PARTICIPANT_ID, INSTRUCTOR_ID, "INSTRUCTOR");
        jdbcTemplate.update("DELETE FROM interaction_events WHERE session_id = ?", SESSION_ID);
    }

    @AfterEach
    void cleanUp() {
        NEXT_CHANGE.remove();
        jdbcTemplate.update("DELETE FROM interaction_events WHERE session_id = ?", SESSION_ID);
        jdbcTemplate.update(
                "DELETE FROM session_participants WHERE id IN (?, ?)",
                STUDENT_PARTICIPANT_ID,
                INSTRUCTOR_PARTICIPANT_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", SESSION_ID);
    }

    @Test
    void 강사가_학생을_음소거하면_이력이_남는다() throws Exception {
        mute(INSTRUCTOR_ID, STUDENT_PARTICIPANT_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.muted").value(true))
                .andExpect(jsonPath("$.data.alreadyMuted").value(false));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT actor_participant_id, event_type FROM interaction_events WHERE session_id = ?", SESSION_ID);
        assertEquals(1, rows.size());
        assertEquals("FORCE_MUTED", rows.getFirst().get("event_type"));
        // 행위자는 강사다. 대상은 payload 에 있다.
        assertEquals(INSTRUCTOR_PARTICIPANT_ID, ((Number) rows.getFirst().get("actor_participant_id")).longValue());
    }

    /** 같은 요청의 재시도다. 결과가 같으므로 성공이되 이력은 늘지 않아야 한다. */
    @Test
    void 이미_음소거면_성공이지만_이력은_늘지_않는다() throws Exception {
        NEXT_CHANGE.set(MediaMuteChange.UNCHANGED);

        mute(INSTRUCTOR_ID, STUDENT_PARTICIPANT_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.muted").value(true))
                .andExpect(jsonPath("$.data.alreadyMuted").value(true));

        assertEquals(0, interactionRowCount());
    }

    /** 성공으로 돌려주면 강사 화면에는 음소거인데 학생 소리는 계속 나간다. 조용해진 줄 알고 수업을 이어가므로 알아챌 수 없다. */
    @Test
    void 미디어_서버를_쓰지_못하면_503_이고_이력도_없다() throws Exception {
        NEXT_CHANGE.set(MediaMuteChange.UNAVAILABLE);

        mute(INSTRUCTOR_ID, STUDENT_PARTICIPANT_ID).andExpect(status().isServiceUnavailable());

        assertEquals(0, interactionRowCount());
    }

    @Test
    void 학생이_제어하려_하면_403() throws Exception {
        mute(STUDENT_ID, STUDENT_PARTICIPANT_ID).andExpect(status().isForbidden());

        assertEquals(0, interactionRowCount());
    }

    /** 강사끼리 끄면 강제 해제가 없어 상대가 스스로 켜기 전까지 수업이 멎는다. */
    @Test
    void 대상이_강사면_403() throws Exception {
        mute(INSTRUCTOR_ID, INSTRUCTOR_PARTICIPANT_ID).andExpect(status().isForbidden());
    }

    @Test
    void 세션_멤버가_아니면_403() throws Exception {
        mute(OUTSIDER_ID, STUDENT_PARTICIPANT_ID).andExpect(status().isForbidden());
    }

    @Test
    void 없는_대상이면_404() throws Exception {
        mute(INSTRUCTOR_ID, UNKNOWN_PARTICIPANT_ID).andExpect(status().isNotFound());
    }

    /** 해제를 제공하지 않는다는 계약이 경로에서 실제로 지켜지는지 본다. */
    @Test
    void 지원하지_않는_동작은_400() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/{sessionId}/moderation", SESSION_ID)
                        .header("Authorization", "Bearer " + token(INSTRUCTOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"UNMUTE\",\"targetParticipantId\":" + STUDENT_PARTICIPANT_ID + "}"))
                .andExpect(status().isBadRequest());

        assertEquals(0, interactionRowCount());
    }

    @Test
    void 인증하지_않으면_401() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/{sessionId}/moderation", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"MUTE\",\"targetParticipantId\":" + STUDENT_PARTICIPANT_ID + "}"))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions mute(long memberId, long targetParticipantId)
            throws Exception {
        return mockMvc.perform(post("/api/v1/sessions/{sessionId}/moderation", SESSION_ID)
                .header("Authorization", "Bearer " + token(memberId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"MUTE\",\"targetParticipantId\":" + targetParticipantId + "}"));
    }

    private int interactionRowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_events WHERE session_id = ?", Integer.class, SESSION_ID);
    }

    private String token(long memberId) {
        return tokenProvider.issueAccessToken(String.valueOf(memberId)).value();
    }

    private void insertMember(long id, String displayName) {
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE display_name = VALUES(display_name)",
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
                "제어 테스트 수업",
                INVITE_CODE,
                utc(now.minusSeconds(600)),
                utc(now),
                utc(now));
    }

    private void insertParticipant(long id, long memberId, String role) {
        jdbcTemplate.update("DELETE FROM session_participants WHERE id = ?", id);
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                SESSION_ID,
                memberId,
                role,
                utc(now.minusSeconds(300)),
                utc(now),
                utc(now),
                utc(now));
    }
}
