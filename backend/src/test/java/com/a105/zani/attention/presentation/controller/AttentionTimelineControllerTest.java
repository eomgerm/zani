package com.a105.zani.attention.presentation.controller;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.TokenProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 타임라인 API 두 개의 웹 레이어 검증. 실제 서명 JWT 로 인증 필터를 통과시키고 실제 행을 넣어 응답을 만든다 — 익명 계약과 {@code null} 직렬화는 실제 응답 본문으로만 확인할 수 있다. 로컬
 * MySQL/Redis 가 떠 있어야 통과하며, 테스트가 넣은 행은 끝나고 지운다.
 */
@SpringBootTest
class AttentionTimelineControllerTest {

    private static final long INSTRUCTOR_ID = 9_300_900L;
    private static final long STRANGER_ID = 9_300_901L;
    private static final long ENDED_SESSION_ID = 9_300_910L;
    private static final long LIVE_SESSION_ID = 9_300_911L;
    private static final long MISSING_SESSION_ID = 9_300_999L;
    private static final long INSTRUCTOR_PARTICIPANT_ID = 9_300_920L;

    /** 학생 5명. 5명 미만 숨김 경계를 넘겨야 비율이 보인다. */
    private static final int STUDENT_COUNT = 5;

    private static final long STUDENT_ID_BASE = 9_300_930L;
    private static final long STUDENT_PARTICIPANT_ID_BASE = 9_300_940L;

    private static long eventId = 9_300_950_000L;

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

    private static long studentId(int index) {
        return STUDENT_ID_BASE + index;
    }

    private static long studentParticipantId(int index) {
        return STUDENT_PARTICIPANT_ID_BASE + index;
    }

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        cleanUp();

        insertMember(INSTRUCTOR_ID, "타임라인 API 테스트 강사");
        insertMember(STRANGER_ID, "타임라인 API 테스트 외부인");
        insertSession(ENDED_SESSION_ID, "ENDED", "TLAPI910");
        insertSession(LIVE_SESSION_ID, "LIVE", "TLAPI911");
        insertParticipant(INSTRUCTOR_PARTICIPANT_ID, ENDED_SESSION_ID, INSTRUCTOR_ID, "INSTRUCTOR");
        for (int index = 0; index < STUDENT_COUNT; index++) {
            insertMember(studentId(index), "타임라인 API 테스트 학생" + index);
            insertParticipant(studentParticipantId(index), ENDED_SESSION_ID, studentId(index), "STUDENT");
            // 0~300초를 10초 간격으로 채운다. 연속 접속 1분을 넘겨야 집계 대상이 된다.
            for (int step = 0; step <= 30; step++) {
                insertEvent(ENDED_SESSION_ID, studentParticipantId(index), step * 10_000L, "ENGAGED");
            }
        }
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update(
                "DELETE FROM attention_events WHERE session_id IN (?, ?)", ENDED_SESSION_ID, LIVE_SESSION_ID);
        jdbcTemplate.update("DELETE FROM check_prompts WHERE session_id IN (?, ?)", ENDED_SESSION_ID, LIVE_SESSION_ID);
        jdbcTemplate.update(
                "DELETE FROM session_participants WHERE session_id IN (?, ?)", ENDED_SESSION_ID, LIVE_SESSION_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE id IN (?, ?)", ENDED_SESSION_ID, LIVE_SESSION_ID);
    }

    private String tokenOf(long memberId) {
        return tokenProvider.issueAccessToken(String.valueOf(memberId)).value();
    }

    @Test
    @DisplayName("강사 응답에 학생 식별자나 학생별 값이 없다")
    void the_group_response_has_no_per_student_fields() throws Exception {
        String body = mockMvc.perform(get("/api/v1/sessions/{id}/reports/attention/group", ENDED_SESSION_ID)
                        .header("Authorization", "Bearer " + tokenOf(INSTRUCTOR_ID)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // 익명 계약이 깨지면 여기서 잡힌다. 필드가 늘어나도 이 목록에 걸리는 개인 필드는 통과하지 못한다.
        assertThat(body).doesNotContain("participantId", "memberId", "studentId", "displayName", "name");
        // 학생 식별자 값 자체가 본문에 실려 나가지 않는지도 본다.
        for (int index = 0; index < STUDENT_COUNT; index++) {
            assertThat(body).doesNotContain(String.valueOf(studentParticipantId(index)));
            assertThat(body).doesNotContain(String.valueOf(studentId(index)));
        }
    }

    @Test
    @DisplayName("집중 흐름과 신호가 각자 격자를 갖는다")
    void the_two_grids_serialize_separately() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{id}/reports/attention/group", ENDED_SESSION_ID)
                        .header("Authorization", "Bearer " + tokenOf(INSTRUCTOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.focusFlow.intervalSeconds").value(30))
                .andExpect(jsonPath("$.data.signals.intervalSeconds").value(5))
                // 300초 세션이면 30초 칸 10개에 5초 점 61개다. 한 배열에 섞을 수 없는 이유가 이 개수 차이다.
                .andExpect(jsonPath("$.data.focusFlow.points.length()").value(10))
                .andExpect(jsonPath("$.data.signals.points.length()").value(61));
    }

    @Test
    @DisplayName("모든 비율은 0.0~1.0 분수다 — 퍼센트가 아니다")
    void group_ratios_are_fractions() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{id}/reports/attention/group", ENDED_SESSION_ID)
                        .header("Authorization", "Bearer " + tokenOf(INSTRUCTOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signals.points[40].offsetSeconds").value(200))
                .andExpect(jsonPath("$.data.signals.points[40].connectedCount").value(STUDENT_COUNT))
                .andExpect(jsonPath("$.data.signals.points[40].eligibleCount").value(STUDENT_COUNT))
                .andExpect(
                        jsonPath("$.data.signals.points[40].checkNeededRatio").value(0.0))
                // 인원이 모자란 구간은 null 이다. 값이 있는 구간은 하나도 1.0 을 넘지 않아야 분수 계약이 지켜진다.
                .andExpect(jsonPath(
                        "$.data.signals.points[*].cameraOffRatio",
                        everyItem(anyOf(nullValue(Double.class), lessThanOrEqualTo(1.0)))));
    }

    @Test
    @DisplayName("집단 집중 흐름은 1~4 단계 평균이다 — 비율과 척도가 다르다")
    void the_group_focus_level_is_on_the_one_to_four_scale() throws Exception {
        // 학생 5명 모두 3단계(ENGAGED)라 칸 평균도 3.0 이다. 퍼센트라면 100 이 나왔을 값이다.
        mockMvc.perform(get("/api/v1/sessions/{id}/reports/attention/group", ENDED_SESSION_ID)
                        .header("Authorization", "Bearer " + tokenOf(INSTRUCTOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.focusFlow.points[4].offsetSeconds").value(120))
                .andExpect(jsonPath("$.data.focusFlow.points[4].focusLevel").value(3.0))
                .andExpect(jsonPath("$.data.focusFlow.points[4].eligibleCount").value(STUDENT_COUNT));
    }

    @Test
    @DisplayName("빈 값은 0 이나 1 이 아니라 null 이다")
    void null_values_stay_null() throws Exception {
        // 세션 시작 직후는 연속 접속 1분을 못 채워 집계 대상이 0 명이다. 0 이나 1단계로 채우면 안 된다.
        mockMvc.perform(get("/api/v1/sessions/{id}/reports/attention/group", ENDED_SESSION_ID)
                        .header("Authorization", "Bearer " + tokenOf(INSTRUCTOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.focusFlow.points[0].eligibleCount").value(0))
                .andExpect(jsonPath("$.data.focusFlow.points[0].focusLevel").value(nullValue()))
                .andExpect(jsonPath("$.data.signals.points[0].eligibleCount").value(0))
                .andExpect(jsonPath("$.data.signals.points[0].checkNeededRatio").value(nullValue()))
                .andExpect(jsonPath("$.data.signals.points[0].cameraOffRatio").value(nullValue()))
                .andExpect(jsonPath("$.data.signals.points[0].confusedRatio").value(nullValue()))
                .andExpect(
                        jsonPath("$.data.signals.points[0].unmeasurableRatio").value(nullValue()));
    }

    @Test
    @DisplayName("내용 구간이 없으면 빈 배열로 나간다 — 248 미완 세션")
    void empty_sections_serialize_as_an_empty_array() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{id}/reports/attention/group", ENDED_SESSION_ID)
                        .header("Authorization", "Bearer " + tokenOf(INSTRUCTOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sections").isArray())
                .andExpect(jsonPath("$.data.sections.length()").value(0));

        mockMvc.perform(get("/api/v1/sessions/{id}/reports/attention/me", ENDED_SESSION_ID)
                        .header("Authorization", "Bearer " + tokenOf(studentId(0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sections").isArray())
                .andExpect(jsonPath("$.data.sections.length()").value(0));
    }

    @Test
    @DisplayName("학생 응답에 단계·확률·세부 신호가 없다")
    void the_personal_response_hides_model_internals() throws Exception {
        String body = mockMvc.perform(get("/api/v1/sessions/{id}/reports/attention/me", ENDED_SESSION_ID)
                        .header("Authorization", "Bearer " + tokenOf(studentId(0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.focusFlow.intervalSeconds").value(30))
                .andExpect(jsonPath("$.data.focusFlow.points[4].offsetSeconds").value(120))
                // 1.00~4.00 단계 평균이다. 강사 응답의 focusLevel 과 같은 척도다.
                .andExpect(jsonPath("$.data.focusFlow.points[4].focusLevel").value(3.0))
                .andExpect(jsonPath("$.data.stateIntervals[0].state").value("GOOD"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain(
                        "attentionScore",
                        "engagementLevel",
                        "confidence",
                        "signalQuality",
                        "detectorOutcome",
                        "average",
                        "participantId");
    }

    @Test
    @DisplayName("응답 어디에도 focusPercent 가 없다 — 옛 계약의 흔적이 남으면 실패한다")
    void the_old_percent_field_is_gone() throws Exception {
        String personal = mockMvc.perform(get("/api/v1/sessions/{id}/reports/attention/me", ENDED_SESSION_ID)
                        .header("Authorization", "Bearer " + tokenOf(studentId(0))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String group = mockMvc.perform(get("/api/v1/sessions/{id}/reports/attention/group", ENDED_SESSION_ID)
                        .header("Authorization", "Bearer " + tokenOf(INSTRUCTOR_ID)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(personal).doesNotContain("focusPercent");
        assertThat(group).doesNotContain("focusPercent");
    }

    @Test
    @DisplayName("상태 구간은 서버가 병합해 내려준다 — 같은 상태가 연달아 오지 않는다")
    void state_intervals_arrive_merged() throws Exception {
        // 0~300초가 전부 3단계라 GOOD 구간 하나로 합쳐져야 한다. 5초마다 60개가 오면 병합이 빠진 것이다.
        mockMvc.perform(get("/api/v1/sessions/{id}/reports/attention/me", ENDED_SESSION_ID)
                        .header("Authorization", "Bearer " + tokenOf(studentId(0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stateIntervals.length()").value(1))
                .andExpect(jsonPath("$.data.stateIntervals[0].startSeconds").value(0));
    }

    @Test
    @DisplayName("강사가 개인 경로를, 학생이 집단 경로를 부르면 403 이다")
    void crossing_roles_is_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{id}/reports/attention/me", ENDED_SESSION_ID)
                        .header("Authorization", "Bearer " + tokenOf(INSTRUCTOR_ID)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/sessions/{id}/reports/attention/group", ENDED_SESSION_ID)
                        .header("Authorization", "Bearer " + tokenOf(studentId(0))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("진행 중인 세션은 409 다")
    void live_session_returns_conflict() throws Exception {
        insertParticipant(INSTRUCTOR_PARTICIPANT_ID + 1, LIVE_SESSION_ID, INSTRUCTOR_ID, "INSTRUCTOR");

        mockMvc.perform(get("/api/v1/sessions/{id}/reports/attention/group", LIVE_SESSION_ID)
                        .header("Authorization", "Bearer " + tokenOf(INSTRUCTOR_ID)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("참가자가 아니면 403 이다")
    void non_participant_returns_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{id}/reports/attention/group", ENDED_SESSION_ID)
                        .header("Authorization", "Bearer " + tokenOf(STRANGER_ID)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("없는 세션도 비멤버에게는 403 이다 — 세션이 있는지조차 알려주지 않는다")
    void a_missing_session_looks_the_same_as_one_you_cannot_see() throws Exception {
        // 404 가 아니다. 접근 판정이 멤버십을 세션 존재보다 먼저 보기 때문에, 참가자가 아닌 호출자는 없는 세션과
        // 남의 세션을 구분할 수 없다. 404 를 주면 세션 번호를 훑어 어떤 수업이 열렸는지 알아낼 수 있다.
        // 되돌리기 전에 ResolveEndedSessionParticipantService 의 판정 순서와 그 테스트를 함께 보라.
        //
        // 멤버인데 세션 행만 사라진 경우는 여전히 404 다. session_participants 가 sessions 를 FK 로 걸고 있어
        // 여기서는 그 상태를 만들 수 없고, ResolveEndedSessionParticipantServiceTest 가 대신 지킨다.
        mockMvc.perform(get("/api/v1/sessions/{id}/reports/attention/group", MISSING_SESSION_ID)
                        .header("Authorization", "Bearer " + tokenOf(INSTRUCTOR_ID)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("인증이 없으면 401 이다")
    void anonymous_access_is_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{id}/reports/attention/group", ENDED_SESSION_ID))
                .andExpect(status().isUnauthorized());
    }

    private void insertMember(long id, String name) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                id,
                "google-" + id,
                id + "@example.com",
                name,
                utc(now),
                utc(now));
    }

    private void insertSession(long sessionId, String status, String inviteCode) {
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'NOT_STARTED', ?, ?, ?)",
                sessionId,
                INSTRUCTOR_ID,
                "타임라인 API 테스트",
                inviteCode,
                status,
                utc(now.minusSeconds(3600)),
                utc(now),
                utc(now));
    }

    private void insertParticipant(long participantId, long sessionId, long memberId, String role) {
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                participantId,
                sessionId,
                memberId,
                role,
                utc(now),
                utc(now),
                utc(now),
                utc(now));
    }

    private void insertEvent(long sessionId, long participantId, long offsetMs, String outcome) {
        jdbcTemplate.update(
                "INSERT INTO attention_events (id, session_id, session_participant_id, detector_outcome,"
                        + " occurred_offset_ms, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                eventId++,
                sessionId,
                participantId,
                outcome,
                offsetMs,
                utc(now));
    }
}
