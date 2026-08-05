package com.a105.zani.report;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.TokenProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class SessionSummaryApiTest {

    private static final long INSTRUCTOR_ID = 9_113_000L;
    private static final long STUDENT_ID = 9_113_001L;
    private static final long OUTSIDER_ID = 9_113_002L;
    private static final long SESSION_ID = 9_113_010L;
    private static final long LIVE_SESSION_ID = 9_113_011L;
    private static final long SESSION_REPORT_ID = 9_113_030L;
    private static final String SUMMARY = "이번 수업은 지역 상태에서 출발해 props drilling, Context 리렌더링 순으로 이어졌습니다.";
    private static final LocalDateTime NOW =
            LocalDateTime.ofInstant(Instant.parse("2026-08-05T01:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        insertMember(INSTRUCTOR_ID, "강사");
        insertMember(STUDENT_ID, "학생");
        insertMember(OUTSIDER_ID, "외부인");
        insertSession(SESSION_ID, "ENDED", "RS113010");
        insertSession(LIVE_SESSION_ID, "LIVE", "RS113011");
        insertParticipant(9_113_020L, SESSION_ID, INSTRUCTOR_ID, "INSTRUCTOR");
        insertParticipant(9_113_021L, SESSION_ID, STUDENT_ID, "STUDENT");
        insertParticipant(9_113_022L, LIVE_SESSION_ID, STUDENT_ID, "STUDENT");
    }

    @Test
    @DisplayName("강사와 학생이 완전히 같은 요약 응답을 받는다")
    void serves_an_identical_summary_to_both_roles() throws Exception {
        insertSessionReport(NOW);

        String asStudent = fetchSummary(STUDENT_ID, SESSION_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value(SUMMARY))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String asInstructor = fetchSummary(INSTRUCTOR_ID, SESSION_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value(SUMMARY))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // 바이트까지 같아야 한다. 역할별로 문장이 갈리면 강사가 학생에게 요약을 가리켜 말할 수 없다.
        assertThat(asStudent).isEqualTo(asInstructor);
    }

    @Test
    @DisplayName("요약이 아직 없으면 404다")
    void reports_not_ready_without_a_summary() throws Exception {
        fetchSummary(STUDENT_ID, SESSION_ID)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_002"));
    }

    @Test
    @DisplayName("요약이 미게시 상태면 404다")
    void reports_not_ready_when_the_summary_is_unpublished() throws Exception {
        insertSessionReport(null);

        // 게시 전 초안은 화면에 나가지 않는다.
        fetchSummary(STUDENT_ID, SESSION_ID)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_002"));
    }

    @Test
    @DisplayName("세션 비참가자는 403이고 요약을 볼 수 없다")
    void hides_the_summary_from_an_outsider() throws Exception {
        insertSessionReport(NOW);

        String body = fetchSummary(OUTSIDER_ID, SESSION_ID)
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain(SUMMARY);
    }

    @Test
    @DisplayName("진행 중 세션은 409다")
    void rejects_a_live_session() throws Exception {
        fetchSummary(STUDENT_ID, LIVE_SESSION_ID).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("인증이 없으면 401이다")
    void rejects_anonymous_access() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/reports/summary", SESSION_ID))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions fetchSummary(long memberId, long sessionId) throws Exception {
        return mockMvc.perform(get("/api/v1/sessions/{sessionId}/reports/summary", sessionId)
                .header(
                        "Authorization",
                        "Bearer "
                                + tokenProvider
                                        .issueAccessToken(String.valueOf(memberId))
                                        .value()));
    }

    private void insertMember(long id, String name) {
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                id,
                "session-summary-api-" + id,
                id + "@session-summary-api.test",
                name,
                NOW,
                NOW);
    }

    private void insertSession(long id, String status, String inviteCode) {
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at) VALUES (?, ?, '수업 요약 API 테스트', ?, ?,"
                        + " 'COMPLETED', ?, ?, ?, ?)",
                id,
                INSTRUCTOR_ID,
                inviteCode,
                status,
                NOW.minusHours(1),
                "ENDED".equals(status) ? NOW : null,
                NOW,
                NOW);
    }

    private void insertParticipant(long id, long sessionId, long memberId, String role) {
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                sessionId,
                memberId,
                role,
                NOW,
                NOW,
                NOW,
                NOW);
    }

    private void insertSessionReport(LocalDateTime publishedAt) {
        jdbcTemplate.update(
                "INSERT INTO session_reports (id, session_id, summary, published_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                SESSION_REPORT_ID,
                SESSION_ID,
                SUMMARY,
                publishedAt,
                NOW,
                NOW);
    }
}
