package com.a105.zani.quiz;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.a105.zani.auth.application.port.TokenProvider;
import com.a105.zani.quiz.application.exception.QuizAlreadySubmittedException;
import com.a105.zani.quiz.application.port.NewQuizAnswer;
import com.a105.zani.quiz.application.port.StudentQuizPort;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 학생 퀴즈 조회·제출 엔드포인트의 전 구간 검증. 로컬 MySQL/Redis가 떠 있어야 통과한다.
 *
 * <p>핵심 계약 — 제출 전 정답·해설 비노출, 일괄 제출 채점, 재제출 409, 세션 참여 학생 본인만(403).
 */
@SpringBootTest
class StudentQuizApiTest {

    private static final long INSTRUCTOR_ID = 9_400_910L;
    private static final long STUDENT_ID = 9_400_911L;
    private static final long OUTSIDER_ID = 9_400_912L;
    private static final long REPORTLESS_STUDENT_ID = 9_400_913L;
    private static final long SESSION_ID = 9_400_914L;
    private static final long INSTRUCTOR_PARTICIPANT_ID = 9_400_915L;
    private static final long STUDENT_PARTICIPANT_ID = 9_400_916L;
    private static final long REPORTLESS_PARTICIPANT_ID = 9_400_917L;
    private static final long STUDENT_REPORT_ID = 9_400_918L;
    private static final long QUIZ_ID = 9_400_919L;
    private static final long QUESTION_1_ID = 9_400_920L;
    private static final long QUESTION_2_ID = 9_400_921L;
    private static final long OPTION_1_CORRECT_ID = 9_400_922L;
    private static final long OPTION_1_WRONG_ID = 9_400_923L;
    private static final long OPTION_2_CORRECT_ID = 9_400_924L;
    private static final long OPTION_2_WRONG_ID = 9_400_925L;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StudentQuizPort studentQuizPort;

    private MockMvc mockMvc;

    private Instant now;

    /** Hibernate가 UTC로 저장(jdbc.time_zone=UTC)하므로, 직접 INSERT 할 때도 UTC 기준 시각을 넣는다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        cleanUpRows();
        insertMember(INSTRUCTOR_ID, "퀴즈 테스트 강사");
        insertMember(STUDENT_ID, "퀴즈 테스트 학생");
        insertMember(OUTSIDER_ID, "퀴즈 테스트 비참여자");
        insertMember(REPORTLESS_STUDENT_ID, "퀴즈 없는 학생");
        insertEndedSession();
        insertParticipant(INSTRUCTOR_PARTICIPANT_ID, INSTRUCTOR_ID, "INSTRUCTOR");
        insertParticipant(STUDENT_PARTICIPANT_ID, STUDENT_ID, "STUDENT");
        insertParticipant(REPORTLESS_PARTICIPANT_ID, REPORTLESS_STUDENT_ID, "STUDENT");
        insertStudentReport();
        insertQuiz();
        insertQuestion(QUESTION_1_ID, 1, "재귀 함수의 종료 조건이 없으면 무엇이 발생하는가?", "종료 조건이 없으면 호출이 무한히 쌓여 스택 오버플로가 난다.");
        insertOption(OPTION_1_CORRECT_ID, QUESTION_1_ID, 1, "스택 오버플로", true);
        insertOption(OPTION_1_WRONG_ID, QUESTION_1_ID, 2, "컴파일 오류", false);
        insertQuestion(QUESTION_2_ID, 2, "꼬리 재귀 최적화의 효과는?", "꼬리 호출이 마지막 연산이면 스택 프레임을 재사용할 수 있다.");
        insertOption(OPTION_2_WRONG_ID, QUESTION_2_ID, 1, "힙 사용량 증가", false);
        insertOption(OPTION_2_CORRECT_ID, QUESTION_2_ID, 2, "스택 프레임 재사용", true);
    }

    @AfterEach
    void tearDown() {
        cleanUpRows();
    }

    @Test
    void respondsUnauthorizedWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/quiz", SESSION_ID)).andExpect(status().isUnauthorized());
    }

    @Test
    void hidesAnswersAndExplanationsBeforeSubmission() throws Exception {
        fetchQuiz(STUDENT_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quizId").value(QUIZ_ID))
                .andExpect(jsonPath("$.data.submitted").value(false))
                .andExpect(jsonPath("$.data.questions.length()").value(2))
                .andExpect(jsonPath("$.data.questions[0].text").isString())
                .andExpect(jsonPath("$.data.questions[0].options.length()").value(2))
                .andExpect(jsonPath("$.data.questions[0].options[0].text").isString())
                // 채점 블록이 아예 없어야 하고, 보기에도 정답 표식이 없어야 한다.
                .andExpect(jsonPath("$.data.questions[0].grading").doesNotExist())
                .andExpect(jsonPath("$.data.questions[1].grading").doesNotExist())
                .andExpect(jsonPath("$.data.questions[0].options[0].correct").doesNotExist())
                .andExpect(content().string(not(containsString("explanation"))))
                .andExpect(content().string(not(containsString("스택 오버플로가 난다"))));
    }

    @Test
    void gradesTheSubmissionAndPersistsTheAnswers() throws Exception {
        submitAnswers(STUDENT_ID, OPTION_1_CORRECT_ID, OPTION_2_WRONG_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quizId").value(QUIZ_ID))
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.correctCount").value(1))
                .andExpect(jsonPath("$.data.results[0].questionId").value(QUESTION_1_ID))
                .andExpect(jsonPath("$.data.results[0].correct").value(true))
                .andExpect(jsonPath("$.data.results[0].correctOptionId").value(OPTION_1_CORRECT_ID))
                .andExpect(jsonPath("$.data.results[1].correct").value(false))
                .andExpect(jsonPath("$.data.results[1].selectedOptionId").value(OPTION_2_WRONG_ID))
                .andExpect(jsonPath("$.data.results[1].correctOptionId").value(OPTION_2_CORRECT_ID))
                .andExpect(jsonPath("$.data.results[1].explanation").isString());

        assertEquals(2, answerCount());
        assertEquals(OPTION_1_CORRECT_ID, selectedOptionOf(QUESTION_1_ID));
        assertEquals(OPTION_2_WRONG_ID, selectedOptionOf(QUESTION_2_ID));
    }

    @Test
    void showsGradingWhenFetchedAfterSubmission() throws Exception {
        submitAnswers(STUDENT_ID, OPTION_1_CORRECT_ID, OPTION_2_WRONG_ID).andExpect(status().isOk());

        fetchQuiz(STUDENT_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submitted").value(true))
                .andExpect(jsonPath("$.data.questions[0].grading.correct").value(true))
                .andExpect(
                        jsonPath("$.data.questions[0].grading.selectedOptionId").value(OPTION_1_CORRECT_ID))
                .andExpect(
                        jsonPath("$.data.questions[0].grading.correctOptionId").value(OPTION_1_CORRECT_ID))
                .andExpect(jsonPath("$.data.questions[0].grading.explanation").isString())
                .andExpect(jsonPath("$.data.questions[1].grading.correct").value(false))
                .andExpect(
                        jsonPath("$.data.questions[1].grading.correctOptionId").value(OPTION_2_CORRECT_ID));
    }

    @Test
    void rejectsASecondSubmissionWithConflict() throws Exception {
        submitAnswers(STUDENT_ID, OPTION_1_CORRECT_ID, OPTION_2_CORRECT_ID).andExpect(status().isOk());

        // 재응시 없음 — 답을 바꿔 다시 보내도 확정된 제출이 그대로 남는다.
        submitAnswers(STUDENT_ID, OPTION_1_WRONG_ID, OPTION_2_WRONG_ID).andExpect(status().isConflict());

        assertEquals(2, answerCount());
        assertEquals(OPTION_1_CORRECT_ID, selectedOptionOf(QUESTION_1_ID));
    }

    @Test
    void rejectsAPartialSubmission() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/{sessionId}/quiz/answers", SESSION_ID)
                        .header("Authorization", bearer(STUDENT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":%d,\"selectedOptionId\":%d}]}"
                                .formatted(QUESTION_1_ID, OPTION_1_CORRECT_ID)))
                .andExpect(status().isBadRequest());

        assertEquals(0, answerCount());
    }

    @Test
    void rejectsAnOptionThatBelongsToAnotherQuestion() throws Exception {
        submitAnswers(STUDENT_ID, OPTION_2_CORRECT_ID, OPTION_2_CORRECT_ID).andExpect(status().isBadRequest());

        assertEquals(0, answerCount());
    }

    @Test
    void rejectsANonParticipantOnBothEndpoints() throws Exception {
        fetchQuiz(OUTSIDER_ID).andExpect(status().isForbidden());
        submitAnswers(OUTSIDER_ID, OPTION_1_CORRECT_ID, OPTION_2_CORRECT_ID).andExpect(status().isForbidden());
    }

    @Test
    void rejectsTheInstructorBecauseQuizzesAreStudentOnly() throws Exception {
        fetchQuiz(INSTRUCTOR_ID).andExpect(status().isForbidden());
    }

    @Test
    void returnsNotFoundWhenThePipelineHasNotGeneratedAQuiz() throws Exception {
        fetchQuiz(REPORTLESS_STUDENT_ID).andExpect(status().isNotFound());
    }

    @Test
    void returnsNotFoundWhileTheSessionIsStillLive() throws Exception {
        jdbcTemplate.update("UPDATE sessions SET status = 'LIVE' WHERE id = ?", SESSION_ID);

        fetchQuiz(STUDENT_ID).andExpect(status().isNotFound());
    }

    /** HTTP 경로는 사전 검사(기존 답 유무)가 먼저 409 를 돌려줘 DB 유니크 경합 분기에 닿지 못한다. 아래 두 건만 포트를 직접 부른다. */
    @Test
    void translatesTheDuplicateKeyRaceIntoAlreadySubmittedAtThePort() {
        Instant answeredAt = Instant.now();
        List<NewQuizAnswer> submission = List.of(
                new NewQuizAnswer(QUESTION_1_ID, OPTION_1_CORRECT_ID, answeredAt),
                new NewQuizAnswer(QUESTION_2_ID, OPTION_2_CORRECT_ID, answeredAt));
        studentQuizPort.saveAnswers(submission);

        assertThrows(QuizAlreadySubmittedException.class, () -> studentQuizPort.saveAnswers(submission));

        // 경합에서 진 쪽은 아무것도 남기지 않고, 먼저 커밋된 제출이 그대로 남는다.
        assertEquals(2, answerCount());
    }

    @Test
    void propagatesANonDuplicateIntegrityViolationInsteadOfMisreportingConflict() {
        // 다른 문항의 보기 — 복합 FK(quiz_question_id, selected_quiz_option_id) 위반이라 중복 키(1062)가 아니다.
        List<NewQuizAnswer> foreignKeyBreaker =
                List.of(new NewQuizAnswer(QUESTION_1_ID, OPTION_2_CORRECT_ID, Instant.now()));

        assertThrows(DataIntegrityViolationException.class, () -> studentQuizPort.saveAnswers(foreignKeyBreaker));

        assertEquals(0, answerCount());
    }

    private ResultActions fetchQuiz(long memberId) throws Exception {
        return mockMvc.perform(
                get("/api/v1/sessions/{sessionId}/quiz", SESSION_ID).header("Authorization", bearer(memberId)));
    }

    private ResultActions submitAnswers(long memberId, long question1OptionId, long question2OptionId)
            throws Exception {
        String body = """
                {"answers":[
                  {"questionId":%d,"selectedOptionId":%d},
                  {"questionId":%d,"selectedOptionId":%d}
                ]}""".formatted(QUESTION_1_ID, question1OptionId, QUESTION_2_ID, question2OptionId);
        return mockMvc.perform(post("/api/v1/sessions/{sessionId}/quiz/answers", SESSION_ID)
                .header("Authorization", bearer(memberId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String bearer(long memberId) {
        return "Bearer "
                + tokenProvider.issueAccessToken(String.valueOf(memberId)).value();
    }

    private int answerCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quiz_answers WHERE quiz_question_id IN (?, ?)",
                Integer.class,
                QUESTION_1_ID,
                QUESTION_2_ID);
    }

    private long selectedOptionOf(long questionId) {
        return jdbcTemplate.queryForObject(
                "SELECT selected_quiz_option_id FROM quiz_answers WHERE quiz_question_id = ?", Long.class, questionId);
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

    private void insertEndedSession() {
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'ENDED', 'COMPLETED', ?, ?, ?, ?)",
                SESSION_ID,
                INSTRUCTOR_ID,
                "학생 퀴즈 테스트",
                "QUIZ9400",
                utc(now.minusSeconds(3_600)),
                utc(now.minusSeconds(1_800)),
                utc(now),
                utc(now));
    }

    private void insertParticipant(long participantId, long memberId, String role) {
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                participantId,
                SESSION_ID,
                memberId,
                role,
                utc(now.minusSeconds(3_600)),
                utc(now.minusSeconds(1_800)),
                utc(now),
                utc(now));
    }

    private void insertStudentReport() {
        jdbcTemplate.update(
                "INSERT INTO student_reports (id, session_id, session_participant_id, participation_summary,"
                        + " published_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                STUDENT_REPORT_ID,
                SESSION_ID,
                STUDENT_PARTICIPANT_ID,
                "재귀 개념 구간에서 집중도가 낮았다.",
                utc(now.minusSeconds(600)),
                utc(now),
                utc(now));
    }

    private void insertQuiz() {
        jdbcTemplate.update(
                "INSERT INTO quizzes (id, student_report_id, title, description, estimated_duration_minutes,"
                        + " published_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                QUIZ_ID,
                STUDENT_REPORT_ID,
                "재귀 함수 복습 퀴즈",
                "수업에서 놓친 재귀 개념을 확인한다.",
                5,
                utc(now.minusSeconds(600)),
                utc(now),
                utc(now));
    }

    private void insertQuestion(long questionId, int order, String text, String explanation) {
        jdbcTemplate.update(
                "INSERT INTO quiz_questions (id, quiz_id, question_text, explanation, question_order,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                questionId,
                QUIZ_ID,
                text,
                explanation,
                order,
                utc(now),
                utc(now));
    }

    private void insertOption(long optionId, long questionId, int order, String text, boolean correct) {
        jdbcTemplate.update(
                "INSERT INTO quiz_options (id, quiz_question_id, option_text, is_correct, option_order,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                optionId,
                questionId,
                text,
                correct,
                order,
                utc(now),
                utc(now));
    }

    private void cleanUpRows() {
        jdbcTemplate.update(
                "DELETE FROM quiz_answers WHERE quiz_question_id IN"
                        + " (SELECT id FROM quiz_questions WHERE quiz_id = ?)",
                QUIZ_ID);
        jdbcTemplate.update(
                "DELETE FROM quiz_options WHERE quiz_question_id IN"
                        + " (SELECT id FROM quiz_questions WHERE quiz_id = ?)",
                QUIZ_ID);
        jdbcTemplate.update("DELETE FROM quiz_questions WHERE quiz_id = ?", QUIZ_ID);
        jdbcTemplate.update("DELETE FROM quizzes WHERE id = ?", QUIZ_ID);
        jdbcTemplate.update("DELETE FROM student_reports WHERE session_id = ?", SESSION_ID);
        // 만료 스윕 스케줄러가 테스트 중에도 돌며 세션 자식 행을 남길 수 있어 자식부터 지운다.
        jdbcTemplate.update("DELETE FROM session_status_changes WHERE session_id = ?", SESSION_ID);
        jdbcTemplate.update("DELETE FROM session_participants WHERE session_id = ?", SESSION_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", SESSION_ID);
    }
}
