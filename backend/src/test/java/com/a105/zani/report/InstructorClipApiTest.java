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

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class InstructorClipApiTest {

    private static final long INSTRUCTOR_ID = 9_308_500L;
    private static final long STUDENT_ID = 9_308_501L;
    private static final long OUTSIDER_ID = 9_308_502L;
    private static final long SESSION_ID = 9_308_510L;
    private static final long LIVE_SESSION_ID = 9_308_511L;
    private static final long INSTRUCTOR_PARTICIPANT_ID = 9_308_520L;
    private static final long STUDENT_PARTICIPANT_ID = 9_308_521L;
    private static final long LIVE_PARTICIPANT_ID = 9_308_522L;
    private static final long SESSION_REPORT_ID = 9_308_530L;
    private static final LocalDateTime NOW =
            LocalDateTime.ofInstant(Instant.parse("2026-08-04T01:00:00Z"), ZoneOffset.UTC);

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
        insertSession(SESSION_ID, "ENDED", "TC308510");
        insertSession(LIVE_SESSION_ID, "LIVE", "TC308511");
        insertParticipant(INSTRUCTOR_PARTICIPANT_ID, SESSION_ID, INSTRUCTOR_ID, "INSTRUCTOR");
        insertParticipant(STUDENT_PARTICIPANT_ID, SESSION_ID, STUDENT_ID, "STUDENT");
        insertParticipant(LIVE_PARTICIPANT_ID, LIVE_SESSION_ID, INSTRUCTOR_ID, "INSTRUCTOR");
    }

    @Test
    @DisplayName("강사는 게시된 수업의 재생 정보를 조회한다 — 녹화 파일이 없으면 recordingUrl만 null이다")
    void returns_the_clip_playback_contract() throws Exception {
        insertSessionReport(NOW);
        insertTranscript();

        fetchClip(INSTRUCTOR_ID, SESSION_ID)
                .andExpect(status().isOk())
                // 최종 MP4 가 아직 없는 환경이다. 클립 전체가 404 로 죽지 않고 이 필드만 비어야 한다.
                .andExpect(jsonPath("$.data.recordingUrl").value(nullValue()))
                // 세션 시작 1시간 전 ~ 종료 = 3,600 초
                .andExpect(jsonPath("$.data.durationSeconds").value(3600))
                .andExpect(jsonPath("$.data.seekTimestamp").value(0))
                // 저장 순서와 무관하게 시작 시각 오름차순이다.
                .andExpect(jsonPath("$.data.transcript.length()").value(2))
                // 화자는 실명이다. 익명 별칭이 보이면 계약 위반이다(REPORT-S-001).
                .andExpect(jsonPath("$.data.transcript[0].speakerName").value("강사"))
                .andExpect(jsonPath("$.data.transcript[0].startSeconds").value(1))
                .andExpect(jsonPath("$.data.transcript[0].endSeconds").value(9))
                .andExpect(jsonPath("$.data.transcript[0].text").value("강사 발화"))
                .andExpect(jsonPath("$.data.transcript[1].speakerName").value("학생"))
                .andExpect(jsonPath("$.data.transcript[1].startSeconds").value(20));
    }

    @Test
    @DisplayName("전사가 아직 없으면 빈 배열이며 오류가 아니다")
    void returns_an_empty_transcript_when_none_exists() throws Exception {
        insertSessionReport(NOW);

        fetchClip(INSTRUCTOR_ID, SESSION_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transcript.length()").value(0));
    }

    @Test
    @DisplayName("인증이 없으면 401이다")
    void rejects_anonymous_access() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/reports/instructor/clip", SESSION_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("학생은 강사 수업 클립을 조회할 수 없다")
    void rejects_the_student() throws Exception {
        insertSessionReport(NOW);

        fetchClip(STUDENT_ID, SESSION_ID).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("세션 비참가자는 세션 존재 여부를 알 수 없는 403이다")
    void hides_the_session_from_an_outsider() throws Exception {
        insertSessionReport(NOW);

        fetchClip(OUTSIDER_ID, SESSION_ID).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("진행 중 세션은 409다")
    void rejects_a_live_session() throws Exception {
        fetchClip(INSTRUCTOR_ID, LIVE_SESSION_ID).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("공통 리포트가 아직 없으면 404다")
    void reports_not_ready_when_the_session_report_is_missing() throws Exception {
        fetchClip(INSTRUCTOR_ID, SESSION_ID)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_002"));
    }

    @Test
    @DisplayName("공통 리포트가 미게시 상태면 404다")
    void reports_not_ready_when_the_session_report_is_unpublished() throws Exception {
        insertSessionReport(null);
        insertTranscript();

        fetchClip(INSTRUCTOR_ID, SESSION_ID)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_002"));
    }

    private ResultActions fetchClip(long memberId, long sessionId) throws Exception {
        return mockMvc.perform(get("/api/v1/sessions/{sessionId}/reports/instructor/clip", sessionId)
                .header(
                        "Authorization",
                        "Bearer "
                                + tokenProvider
                                        .issueAccessToken(String.valueOf(memberId))
                                        .value()));
    }

    /** 화자 키는 참가자 ID 다. 실명으로 푸는 일은 서버가 조립 시점에 끝낸다 — 이름을 JSON 에 굳히지 않는다. */
    private void insertTranscript() {
        jdbcTemplate.update(
                "INSERT INTO transcripts (id, session_id, transcript_document, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                9_308_600L,
                SESSION_ID,
                """
                {
                  "schemaVersion": 1,
                  "segments": [
                    {"sessionParticipantId": %d, "startOffsetMs": 20000, "endOffsetMs": 25400, "text": "학생 발화"},
                    {"sessionParticipantId": %d, "startOffsetMs": 1500, "endOffsetMs": 9900, "text": "강사 발화"}
                  ]
                }
                """.formatted(STUDENT_PARTICIPANT_ID, INSTRUCTOR_PARTICIPANT_ID),
                NOW,
                NOW);
    }

    private void insertMember(long id, String name) {
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                id,
                "instructor-clip-api-" + id,
                id + "@instructor-clip-api.test",
                name,
                NOW,
                NOW);
    }

    private void insertSession(long id, String status, String inviteCode) {
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at) VALUES (?, ?, '강사 수업 클립 API 테스트', ?, ?,"
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

    /** 수업 클립의 게이트는 공통 리포트의 게시다. 미게시 초안은 화면에 나가지 않는다. */
    private void insertSessionReport(LocalDateTime publishedAt) {
        jdbcTemplate.update(
                "INSERT INTO session_reports (id, session_id, summary, published_at, created_at, updated_at)"
                        + " VALUES (?, ?, '수업 요약', ?, ?, ?)",
                SESSION_REPORT_ID,
                SESSION_ID,
                publishedAt,
                NOW,
                NOW);
    }
}
