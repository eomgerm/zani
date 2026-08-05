package com.a105.zani.report;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.TokenProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 강사 리포트의 접근 차단과 개인정보 미노출 검증.
 *
 * <p><b>이 파일이 지키는 것은 두 가지다.</b> 하나는 남의 수업 리포트를 세션 ID 만 바꿔 읽지 못하는 것(IDOR), 다른 하나는 강사 화면에서 특정 학생을 짚어낼 수 없는 것이다
 * (REPORT-I-002). 뒤쪽은 응답에 학생 식별자가 <b>어떤 형태로도</b> 없어야 성립하므로, 필드 이름을 하나씩 확인하는 대신 응답 전체를 훑는다.
 *
 * <p>필드가 늘어날 때 이 테스트가 먼저 깨져야 한다. 강사 화면에서 개인을 짚어낼 수 있게 되면 참여도 측정이 감시로 바뀐다.
 */
@SpringBootTest
class InstructorReportSecurityTest {

    private static final long OWNER_ID = 9_200_910L;
    private static final long OTHER_INSTRUCTOR_ID = 9_200_911L;
    private static final long STUDENT_ID = 9_200_912L;
    private static final long STRANGER_ID = 9_200_913L;

    private static final long SESSION_ID = 9_200_920L;
    private static final long OTHER_SESSION_ID = 9_200_921L;

    private static final long OWNER_PARTICIPANT_ID = 9_200_930L;
    private static final long STUDENT_PARTICIPANT_ID = 9_200_931L;
    private static final long OTHER_PARTICIPANT_ID = 9_200_932L;

    private static final long REPORT_ID = 9_200_940L;

    /** 응답에서 이 값들이 보이면 학생을 짚어낼 수 있게 된 것이다. */
    private static final Set<Long> STUDENT_IDENTIFIERS = Set.of(STUDENT_ID, STUDENT_PARTICIPANT_ID);

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
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        cleanUp();

        insertMember(OWNER_ID, "박강사");
        insertMember(OTHER_INSTRUCTOR_ID, "남의 수업 강사");
        insertMember(STUDENT_ID, "김민수");
        insertMember(STRANGER_ID, "아무 관계 없는 사람");

        insertSession(SESSION_ID, OWNER_ID, "SEC00111");
        insertSession(OTHER_SESSION_ID, OTHER_INSTRUCTOR_ID, "SEC00112");

        insertParticipant(OWNER_PARTICIPANT_ID, SESSION_ID, OWNER_ID, "INSTRUCTOR");
        insertParticipant(STUDENT_PARTICIPANT_ID, SESSION_ID, STUDENT_ID, "STUDENT");
        insertParticipant(OTHER_PARTICIPANT_ID, OTHER_SESSION_ID, OTHER_INSTRUCTOR_ID, "INSTRUCTOR");

        insertPublishedReport();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM instructor_report_scores WHERE instructor_report_id = ?", REPORT_ID);
        jdbcTemplate.update("DELETE FROM instructor_report_insights WHERE instructor_report_id = ?", REPORT_ID);
        jdbcTemplate.update("DELETE FROM instructor_report_tips WHERE instructor_report_id = ?", REPORT_ID);
        jdbcTemplate.update("DELETE FROM instructor_reports WHERE id = ?", REPORT_ID);
        jdbcTemplate.update(
                "DELETE FROM session_participants WHERE session_id IN (?, ?)", SESSION_ID, OTHER_SESSION_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE id IN (?, ?)", SESSION_ID, OTHER_SESSION_ID);
    }

    /** 세션 ID 만 바꿔 남의 리포트를 읽는 경로다. 인증은 통과하므로 401 로는 막히지 않고, 이 판정이 유일한 방어선이다. */
    @Test
    void 타_강사는_남의_수업_리포트를_읽지_못한다() throws Exception {
        report(OTHER_INSTRUCTOR_ID).andExpect(status().isForbidden());
    }

    /** 같은 수업의 학생이다. 멤버십은 있지만 강사 리포트는 학생이 볼 문서가 아니다. */
    @Test
    void 같은_수업_학생도_강사_리포트를_읽지_못한다() throws Exception {
        report(STUDENT_ID).andExpect(status().isForbidden());
    }

    /**
     * 세션 ID 를 훑어 존재 여부를 캐낼 수 없어야 한다.
     *
     * <p>있는 수업의 비참가자와 아예 없는 수업이 <b>같은 응답</b>을 받는 것이 그 조건이다. 참가자 조회를 세션 조회보다 먼저 하기 때문에 둘 다 403 으로 떨어진다 — 404 로 감추는 대신
     * 403 으로 뭉개는 방식이며, 감추는 목적은 똑같이 달성된다.
     *
     * <p>두 값을 함께 단언하는 이유: 한쪽만 보면 나중에 판정 순서가 뒤집혀 없는 세션이 404 가 되어도 테스트가 통과한다. 그때 열리는 것이 바로 열거 통로다.
     */
    @Test
    void 없는_수업과_남의_수업이_같은_응답을_받는다() throws Exception {
        int notAParticipant = report(STRANGER_ID).andReturn().getResponse().getStatus();
        int noSuchSession = mockMvc.perform(get("/api/v1/sessions/{sessionId}/reports/instructor", 9_209_999L)
                        .header("Authorization", "Bearer " + token(STRANGER_ID)))
                .andReturn()
                .getResponse()
                .getStatus();

        assertEquals(notAParticipant, noSuchSession, "있는 수업의 비참가자와 없는 수업이 다른 응답을 받으면 세션 ID 로 존재 여부를 캐낼 수 있다");
    }

    @Test
    void 인증하지_않으면_401() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/reports/instructor", SESSION_ID))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 응답 어디에도 학생을 가리키는 값이 없어야 한다.
     *
     * <p>필드 이름을 하나씩 보지 않고 본문 전체에서 식별자를 찾는 이유: 나중에 누가 필드를 늘렸을 때 그 이름을 이 테스트가 알 수 없기 때문이다. 값으로 찾으면 통로가 무엇이든 걸린다.
     */
    @Test
    void 응답에_학생_식별자가_담기지_않는다() throws Exception {
        String body = report(OWNER_ID)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        for (Long identifier : STUDENT_IDENTIFIERS) {
            assertFalse(containsNumber(body, identifier), "강사 리포트 응답에 학생 식별자가 담겼다: " + identifier + " — 응답: " + body);
        }
        // 이름도 마찬가지다. 식별자를 가려도 이름이 남으면 누구인지 알 수 있다.
        assertFalse(body.contains("김민수"), "강사 리포트 응답에 학생 이름이 담겼다: " + body);
    }

    /** 위 검사가 실제로 값을 찾아낼 수 있는지 확인한다. 늘 통과하는 검사는 아무것도 지키지 않는다. */
    @Test
    void 식별자_검사가_실제로_값을_잡아낸다() {
        assertTrue(containsNumber("{\"actorParticipantId\":" + STUDENT_PARTICIPANT_ID + "}", STUDENT_PARTICIPANT_ID));
        assertFalse(containsNumber("{\"score\":88}", STUDENT_PARTICIPANT_ID));
    }

    /** 숫자를 <b>토큰 단위</b>로 찾는다. 단순 문자열 포함으로 보면 더 긴 숫자의 일부에도 걸려, 관계없는 값 때문에 실패하는 테스트가 된다. */
    private static boolean containsNumber(String body, long value) {
        Matcher matcher = Pattern.compile("(?<!\\d)" + value + "(?!\\d)").matcher(body);
        return matcher.find();
    }

    private ResultActions report(long memberId) throws Exception {
        return mockMvc.perform(get("/api/v1/sessions/{sessionId}/reports/instructor", SESSION_ID)
                .header("Authorization", "Bearer " + token(memberId)));
    }

    private String token(long memberId) {
        return tokenProvider.issueAccessToken(String.valueOf(memberId)).value();
    }

    private void insertPublishedReport() {
        jdbcTemplate.update(
                "INSERT INTO instructor_reports (id, session_id, overall_feedback, published_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                REPORT_ID,
                SESSION_ID,
                "전반적으로 흐름이 좋았습니다.",
                utc(now),
                utc(now),
                utc(now));
        jdbcTemplate.update(
                "INSERT INTO instructor_report_scores (id, instructor_report_id, evaluation_type, score,"
                        + " created_at, updated_at) VALUES (?, ?, 'DELIVERY', 88, ?, ?)",
                REPORT_ID + 1,
                REPORT_ID,
                utc(now),
                utc(now));
        jdbcTemplate.update(
                "INSERT INTO instructor_report_insights (id, instructor_report_id, insight_type, content,"
                        + " started_offset_ms, ended_offset_ms, created_at, updated_at)"
                        + " VALUES (?, ?, 'LOW_FOCUS_SECTION', '예외 처리 구간에서 집중도가 낮았어요.', 4800000, 6000000, ?, ?)",
                REPORT_ID + 2,
                REPORT_ID,
                utc(now),
                utc(now));
        jdbcTemplate.update(
                "INSERT INTO instructor_report_tips (id, instructor_report_id, tip_type, title, content,"
                        + " created_at, updated_at) VALUES (?, ?, 'INTERACTION', '질문 응답 시간 확보', '질문 시간을 확보해보세요.', ?, ?)",
                REPORT_ID + 3,
                REPORT_ID,
                utc(now),
                utc(now));
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

    private void insertSession(long id, long hostMemberId, String inviteCode) {
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at)"
                        + " VALUES (?, ?, '보안 테스트 수업', ?, 'ENDED', 'NOT_STARTED', ?, ?, ?, ?)",
                id,
                hostMemberId,
                inviteCode,
                utc(now.minusSeconds(4_500)),
                utc(now.minusSeconds(60)),
                utc(now),
                utc(now));
    }

    private void insertParticipant(long id, long sessionId, long memberId, String role) {
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                sessionId,
                memberId,
                role,
                utc(now.minusSeconds(4_500)),
                utc(now),
                utc(now),
                utc(now));
    }
}
