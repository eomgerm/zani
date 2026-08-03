package com.a105.zani.session;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.TokenProvider;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 화면 공유 엔드포인트의 전 구간 검증. 로컬 MySQL/Redis 가 떠 있어야 통과한다.
 *
 * <p><b>단위 테스트로는 이 파일이 보는 것을 볼 수 없다.</b> {@code ScreenShareServiceTest} 는 유스케이스를 직접 부르므로 경로·인증·직렬화·예외→상태코드 매핑을 지나지 않는다.
 * "세션당 활성 공유 1명"(FRD §10.2)이라는 정책이 실제로 거절로 나타나는 지점은 그 매핑이다.
 *
 * <p><b>같은 409 라도 사유가 갈린다.</b> 공유 중(SESSION_APP_007)과 종료된 세션(MEDIA_TOKEN_003)이 같은 상태 코드를 쓰는데, 클라이언트가 "잠시 뒤 다시"와 "끝난
 * 수업"을 구분하려면 본문 code 가 달라야 한다. 상태 코드만 보면 그 차이가 드러나지 않으므로 code 까지 단언한다.
 */
@SpringBootTest
class ScreenShareApiIntegrationTest {

    private static final long INSTRUCTOR_ID = 9_900_910L;
    private static final long SHARER_ID = 9_900_911L;
    private static final long OTHER_STUDENT_ID = 9_900_912L;
    private static final long OUTSIDER_ID = 9_900_913L;

    private static final long SESSION_ID = 9_900_914L;
    private static final long INSTRUCTOR_PARTICIPANT_ID = 9_900_915L;
    private static final long SHARER_PARTICIPANT_ID = 9_900_916L;
    private static final long OTHER_PARTICIPANT_ID = 9_900_917L;

    /** {@code invite_code} 는 CHAR(8) 이다. 세션 ID 를 잘라 쓰면 자릿수가 바뀔 때 깨진다. */
    private static final String INVITE_CODE = "SHR00067";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private MockMvc mockMvc;
    private Instant now;

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String slotKey() {
        return "session:" + SESSION_ID + ":screenshare";
    }

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        insertMember(INSTRUCTOR_ID, "박강사");
        insertMember(SHARER_ID, "김민수");
        insertMember(OTHER_STUDENT_ID, "이지은");
        insertMember(OUTSIDER_ID, "남의 수업 사람");
        insertLiveSession();
        insertParticipant(INSTRUCTOR_PARTICIPANT_ID, INSTRUCTOR_ID, "INSTRUCTOR");
        insertParticipant(SHARER_PARTICIPANT_ID, SHARER_ID, "STUDENT");
        insertParticipant(OTHER_PARTICIPANT_ID, OTHER_STUDENT_ID, "STUDENT");
        redisTemplate.delete(slotKey());
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(slotKey());
        jdbcTemplate.update(
                "DELETE FROM session_participants WHERE id IN (?, ?, ?)",
                INSTRUCTOR_PARTICIPANT_ID,
                SHARER_PARTICIPANT_ID,
                OTHER_PARTICIPANT_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", SESSION_ID);
    }

    @Test
    void 공유자가_없으면_슬롯을_얻는다() throws Exception {
        startShare(SHARER_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sharing").value(true))
                // TSID 는 JS 안전 정수를 넘을 수 있어 문자열로 내린다.
                .andExpect(jsonPath("$.data.sharerParticipantId").value(String.valueOf(SHARER_PARTICIPANT_ID)));
    }

    /** 역할 제한이 없다 — 강사만 공유할 수 있는 기능이 아니다. */
    @Test
    void 강사도_학생도_공유할_수_있다() throws Exception {
        startShare(INSTRUCTOR_ID).andExpect(status().isOk());
    }

    @Test
    void 다른_참가자가_공유_중이면_409() throws Exception {
        startShare(SHARER_ID).andExpect(status().isOk());

        startShare(OTHER_STUDENT_ID)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_APP_007"));
    }

    /** 공유 중인 FE 는 TTL 을 늘리려고 같은 요청을 주기적으로 다시 보낸다. 그것이 거절되면 공유 도중 슬롯을 잃는다. */
    @Test
    void 같은_참가자가_다시_요청하면_갱신되고_200() throws Exception {
        startShare(SHARER_ID).andExpect(status().isOk());

        startShare(SHARER_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sharerParticipantId").value(String.valueOf(SHARER_PARTICIPANT_ID)));
    }

    /** 공유 중과 같은 409 지만 사유가 다르다. 클라이언트는 "잠시 뒤 다시"와 "끝난 수업"을 다르게 안내해야 한다. */
    @Test
    void 이미_종료된_세션이면_409이고_사유가_다르다() throws Exception {
        jdbcTemplate.update("UPDATE sessions SET status = 'ENDED', ended_at = ? WHERE id = ?", utc(now), SESSION_ID);

        startShare(SHARER_ID)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEDIA_TOKEN_003"));
    }

    @Test
    void 세션_멤버가_아니면_403() throws Exception {
        startShare(OUTSIDER_ID).andExpect(status().isForbidden());
    }

    @Test
    void 인증하지_않으면_401() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/{sessionId}/screen-share", SESSION_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 소유자가_해제하면_다음_사람이_공유할_수_있다() throws Exception {
        startShare(SHARER_ID).andExpect(status().isOk());

        stopShare(SHARER_ID).andExpect(status().isOk());

        startShare(OTHER_STUDENT_ID).andExpect(status().isOk());
    }

    /** 앞사람이 뒤늦게 보낸 정리 요청으로 지금 공유 중인 사람이 끊기면 안 된다. 그래도 오류는 아니다(멱등). */
    @Test
    void 소유자가_아닌_해제는_200이지만_슬롯을_빼앗지_않는다() throws Exception {
        startShare(SHARER_ID).andExpect(status().isOk());

        stopShare(OTHER_STUDENT_ID).andExpect(status().isOk());

        startShare(OTHER_STUDENT_ID)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_APP_007"));
    }

    private ResultActions startShare(long memberId) throws Exception {
        return mockMvc.perform(post("/api/v1/sessions/{sessionId}/screen-share", SESSION_ID)
                .header("Authorization", "Bearer " + token(memberId)));
    }

    private ResultActions stopShare(long memberId) throws Exception {
        return mockMvc.perform(delete("/api/v1/sessions/{sessionId}/screen-share", SESSION_ID)
                .header("Authorization", "Bearer " + token(memberId)));
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
                "화면 공유 테스트 수업",
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
