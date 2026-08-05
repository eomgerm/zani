package com.a105.zani.report;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 강사 리포트 조회의 전 구간 검증. 로컬 MySQL 이 떠 있어야 통과한다.
 *
 * <p><b>단위 테스트로는 이 파일이 보는 것을 볼 수 없다.</b> 서비스를 직접 부르면 경로·인증·직렬화·예외에서 상태코드로 가는 매핑을 지나지 않는다. 특히 "아직 만들어지지 않음" 이 어떤 상태 코드로
 * 나가는지는 그 매핑에서만 드러난다.
 *
 * <p>권한 차단은 {@code InstructorReportSecurityTest} 가 따로 본다. 여기서는 정상 경로와 미완성 분기를 다룬다.
 */
@SpringBootTest
class InstructorReportApiIntegrationTest {

    private static final long INSTRUCTOR_ID = 9_100_910L;
    private static final long STUDENT_ID = 9_100_911L;
    private static final long SECOND_STUDENT_ID = 9_100_912L;

    private static final long ENDED_SESSION_ID = 9_100_920L;
    private static final long LIVE_SESSION_ID = 9_100_921L;

    private static final long INSTRUCTOR_PARTICIPANT_ID = 9_100_930L;
    private static final long STUDENT_PARTICIPANT_ID = 9_100_931L;
    private static final long SECOND_STUDENT_PARTICIPANT_ID = 9_100_933L;
    private static final long LIVE_INSTRUCTOR_PARTICIPANT_ID = 9_100_932L;

    private static final long REPORT_ID = 9_100_940L;

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

        insertMember(INSTRUCTOR_ID, "박강사");
        insertMember(STUDENT_ID, "김민수");
        insertMember(SECOND_STUDENT_ID, "이지은");

        insertSession(ENDED_SESSION_ID, "끝난 수업", "REP00109", "ENDED", now.minusSeconds(60));
        insertSession(LIVE_SESSION_ID, "진행 중 수업", "REP00110", "LIVE", null);

        insertParticipant(INSTRUCTOR_PARTICIPANT_ID, ENDED_SESSION_ID, INSTRUCTOR_ID, "INSTRUCTOR");
        insertParticipant(STUDENT_PARTICIPANT_ID, ENDED_SESSION_ID, STUDENT_ID, "STUDENT");
        insertParticipant(SECOND_STUDENT_PARTICIPANT_ID, ENDED_SESSION_ID, SECOND_STUDENT_ID, "STUDENT");
        insertParticipant(LIVE_INSTRUCTOR_PARTICIPANT_ID, LIVE_SESSION_ID, INSTRUCTOR_ID, "INSTRUCTOR");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM instructor_report_scores WHERE instructor_report_id = ?", REPORT_ID);
        jdbcTemplate.update("DELETE FROM instructor_report_insights WHERE instructor_report_id = ?", REPORT_ID);
        jdbcTemplate.update("DELETE FROM instructor_reports WHERE id = ?", REPORT_ID);
        jdbcTemplate.update(
                "DELETE FROM student_reports WHERE session_id IN (?, ?)", ENDED_SESSION_ID, LIVE_SESSION_ID);
        jdbcTemplate.update(
                "DELETE FROM session_sections WHERE session_id IN (?, ?)", ENDED_SESSION_ID, LIVE_SESSION_ID);
        jdbcTemplate.update(
                "DELETE FROM session_participants WHERE session_id IN (?, ?)", ENDED_SESSION_ID, LIVE_SESSION_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE id IN (?, ?)", ENDED_SESSION_ID, LIVE_SESSION_ID);
    }

    @Test
    void 강사가_자기_수업의_리포트를_받는다() throws Exception {
        insertPublishedReport();
        insertScore("DELIVERY", 88);
        insertInsight("어려운 구간 보강", "예외 처리 구간에서 집중도가 낮았어요.", "추가 예시 코드와 실습 시간을 늘려보세요.", 4_800_000L, 6_000_000L);

        report(INSTRUCTOR_ID, ENDED_SESSION_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overallFeedback").value("전반적으로 흐름이 좋았습니다."))
                .andExpect(jsonPath("$.data.scores[0].evaluationType").value("DELIVERY"))
                // 0~100 점수다. 퍼센트로 오해하면 화면이 100 을 곱한다.
                .andExpect(jsonPath("$.data.scores[0].score").value(88))
                // 250 이 팁 테이블을 지우고 제안을 인사이트 안으로 접었다(V20). 한 장에 제목·근거·제안이 함께 온다.
                .andExpect(jsonPath("$.data.insights[0].title").value("어려운 구간 보강"))
                .andExpect(jsonPath("$.data.insights[0].content").value("예외 처리 구간에서 집중도가 낮았어요."))
                .andExpect(jsonPath("$.data.insights[0].suggestion").value("추가 예시 코드와 실습 시간을 늘려보세요."))
                .andExpect(jsonPath("$.data.insights[0].startedOffsetMs").value(4_800_000L));
    }

    /** 한눈에 보기 타일이 쓰는 값이다. 집중 구간 비율은 여기 없다 — 집중 흐름 응답에서 화면이 계산한다. */
    @Test
    void 한눈에_보기_집계를_함께_내린다() throws Exception {
        insertPublishedReport();

        report(INSTRUCTOR_ID, ENDED_SESSION_ID)
                .andExpect(status().isOk())
                // 강사는 세지 않는다. 이 세션의 참가자 셋 중 학생은 둘이다.
                .andExpect(jsonPath("$.data.stats.studentCount").value(2))
                .andExpect(jsonPath("$.data.stats.durationSeconds").value(4_440))
                .andExpect(jsonPath("$.data.stats.alertCount").value(0));
    }

    /**
     * 질문 수는 AI 가 판단해 리포트 행에 굳혀 둔 값이다(V19).
     *
     * <p>채팅 행을 세지 않는다 — 공개 채팅에는 "감사합니다" 도 같은 모양으로 들어오고, 물음표만 찾으면 "이 부분 다시 설명해주실 수 있나요" 를 놓친다. 조회할 때마다 다시 세지도 않는다. 같은
     * 채팅을 다시 세면 모델이 다르게 판단할 수 있고, 그러면 강사가 어제 본 숫자와 오늘 본 숫자가 달라진다.
     */
    @Test
    void 질문_수를_저장된_판정_그대로_내린다() throws Exception {
        insertReport(now, 184);

        report(INSTRUCTOR_ID, ENDED_SESSION_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats.questionCount").value(184));
    }

    /** 아무도 질문하지 않은 것과 분석이 값을 내지 못한 것은 화면에서 다르게 보여야 한다. 0 으로 낮추면 둘이 같아진다. */
    @Test
    void 질문_수를_알_수_없으면_0_이_아니라_비운다() throws Exception {
        insertReport(now, null);

        report(INSTRUCTOR_ID, ENDED_SESSION_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats.questionCount").value((Object) null));
    }

    /** 위와 갈려야 하는 값이다. 둘 다 비어 오면 화면이 두 사실을 구분할 수 없다. */
    @Test
    void 질문이_정말_0_건이면_0_을_지킨다() throws Exception {
        insertReport(now, 0);

        report(INSTRUCTOR_ID, ENDED_SESSION_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats.questionCount").value(0));
    }

    /** 수업 전체를 가리키는 인사이트다. 화면이 구간 배지를 붙일지 말지가 이 값으로 갈린다. */
    @Test
    void 구간이_없는_인사이트는_시각이_비어_온다() throws Exception {
        insertPublishedReport();
        insertInsight("전체 흐름", "전반적으로 설명 순서가 자연스러웠어요.", null, null, null);

        report(INSTRUCTOR_ID, ENDED_SESSION_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.insights[0].startedOffsetMs").value((Object) null))
                .andExpect(jsonPath("$.data.insights[0].endedOffsetMs").value((Object) null));
    }

    /** 내용 타임라인(248)이 아직 안 돌아간 세션이다. 리포트 자체는 정상이므로 오류로 다루지 않는다. */
    @Test
    void 내용_구간이_없으면_빈_배열이다() throws Exception {
        insertPublishedReport();

        report(INSTRUCTOR_ID, ENDED_SESSION_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sections.length()").value(0));
    }

    /**
     * 아직 만들어지지 않은 리포트다.
     *
     * <p>404 인 것은 학생 리포트(112)와 맞춘 결과다. "없는 것이 아니라 아직인 것" 이라는 이유로 409 가 낫다고 보지만, 같은 리포트 기능에서 학생 화면과 강사 화면이 다른 코드를 받으면
     * 화면이 두 규칙을 알아야 한다. 바꾸려면 양쪽을 함께 바꿔야 한다.
     */
    @Test
    void 리포트가_아직_없으면_404() throws Exception {
        report(INSTRUCTOR_ID, ENDED_SESSION_ID)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_002"));
    }

    /** 행은 만들어졌지만 아직 공개 전이다. 중간 상태를 화면에 내보내면 반쯤 만들어진 리포트를 읽게 된다. */
    @Test
    void 공개_전이면_404() throws Exception {
        insertReport(null);
        insertScore("DELIVERY", 88);

        report(INSTRUCTOR_ID, ENDED_SESSION_ID)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_002"));
    }

    /** 리포트는 수업이 끝난 뒤에만 만든다. 진행 중에 열면 아직 없는 것이 당연하다. */
    @Test
    void 진행_중인_수업이면_409() throws Exception {
        report(INSTRUCTOR_ID, LIVE_SESSION_ID).andExpect(status().isConflict());
    }

    @Test
    void 인증하지_않으면_401() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/reports/instructor", ENDED_SESSION_ID))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions report(long memberId, long sessionId) throws Exception {
        return mockMvc.perform(get("/api/v1/sessions/{sessionId}/reports/instructor", sessionId)
                .header("Authorization", "Bearer " + token(memberId)));
    }

    private String token(long memberId) {
        return tokenProvider.issueAccessToken(String.valueOf(memberId)).value();
    }

    private void insertPublishedReport() {
        insertReport(now);
    }

    private void insertReport(Instant publishedAt) {
        insertReport(publishedAt, null);
    }

    /** {@code questionCount} 의 {@code null} 은 "분석이 값을 내지 못함" 이며 0 이 아니다. */
    private void insertReport(Instant publishedAt, Integer questionCount) {
        jdbcTemplate.update("DELETE FROM instructor_reports WHERE id = ?", REPORT_ID);
        jdbcTemplate.update(
                "INSERT INTO instructor_reports (id, session_id, overall_feedback, question_count, published_at,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                REPORT_ID,
                ENDED_SESSION_ID,
                "전반적으로 흐름이 좋았습니다.",
                questionCount,
                publishedAt == null ? null : utc(publishedAt),
                utc(now),
                utc(now));
    }

    private void insertScore(String evaluationType, int score) {
        jdbcTemplate.update(
                "INSERT INTO instructor_report_scores (id, instructor_report_id, evaluation_type, score,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                REPORT_ID + 1,
                REPORT_ID,
                evaluationType,
                score,
                utc(now),
                utc(now));
    }

    /** 유형 컬럼이 없다 — 250 이 지웠다(V20). AI 가 제목을 직접 짓고 제안까지 한 행에 담는다. */
    private void insertInsight(
            String title, String content, String suggestion, Long startedOffsetMs, Long endedOffsetMs) {
        jdbcTemplate.update(
                "INSERT INTO instructor_report_insights (id, instructor_report_id, title, content, suggestion,"
                        + " started_offset_ms, ended_offset_ms, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                REPORT_ID + 2,
                REPORT_ID,
                title,
                content,
                suggestion,
                startedOffsetMs,
                endedOffsetMs,
                utc(now),
                utc(now));
    }

    /** 질문 수는 학생 리포트에 저장된 판정이다. {@code null} 은 "분석이 값을 내지 못함" 이며 0 이 아니다. */
    private void insertStudentReport(long participantId, Integer questionCount) {
        jdbcTemplate.update(
                "INSERT INTO student_reports (id, session_id, session_participant_id, participation_summary,"
                        + " question_count, published_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, '요약', ?, ?, ?, ?)",
                participantId,
                ENDED_SESSION_ID,
                participantId,
                questionCount,
                utc(now),
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

    private void insertSession(long id, String title, String inviteCode, String status, Instant endedAt) {
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'NOT_STARTED', ?, ?, ?, ?)",
                id,
                INSTRUCTOR_ID,
                title,
                inviteCode,
                status,
                utc(now.minusSeconds(4_500)),
                endedAt == null ? null : utc(endedAt),
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
