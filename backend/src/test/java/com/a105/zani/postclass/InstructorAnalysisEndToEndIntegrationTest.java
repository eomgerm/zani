package com.a105.zani.postclass;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.postclass.application.analyzeinstructor.AnalyzeSessionInstructorCommand;
import com.a105.zani.postclass.application.analyzeinstructor.AnalyzeSessionInstructorUseCase;
import com.a105.zani.postclass.application.analyzeinstructor.InstructorAnalysisOutcome;
import com.a105.zani.postclass.application.port.InstructorAnalysis;
import com.a105.zani.postclass.application.port.InstructorAnalysisPort;
import com.a105.zani.postclass.application.port.InstructorAnalysisRequest;
import com.a105.zani.postclass.infrastructure.gms.GmsInstructorAnalysisMockAdapter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 강사 분석을 입력 조회부터 적재까지 한 번 통과시킨다. (S15P11A105-250 완료 조건)
 *
 * <p>LLM 은 mock 어댑터를 쓰고, 그 앞에 요청을 기록하는 대역을 끼워 GMS 로 나갈 본문에 개인정보가 없는지 함께 본다. 로컬 MySQL·Redis 가 떠 있어야 통과한다.
 */
@SpringBootTest
@Transactional
class InstructorAnalysisEndToEndIntegrationTest {

    /** 팁 이력 3건 — 티켓의 Given 이다. */
    private static final int TIP_HISTORY_COUNT = 3;

    private static final String STUDENT_NAME = "김학생";
    private static final String STUDENT_EMAIL_LOCAL = "student-privacy-probe";

    @Autowired
    private AnalyzeSessionInstructorUseCase useCase;

    @Autowired
    private RecordingAnalysisPort recordingPort;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long sessionId;
    private long instructorParticipantId;
    private long studentParticipantId;

    @BeforeEach
    void setUp() {
        recordingPort.clear();
        long hostMemberId = insertMember("담당 강사", "instructor-" + TsidGenerator.generate());
        long studentMemberId = insertMember(STUDENT_NAME, STUDENT_EMAIL_LOCAL);
        sessionId = insertSession(hostMemberId);
        instructorParticipantId = insertParticipant(hostMemberId, "INSTRUCTOR");
        studentParticipantId = insertParticipant(studentMemberId, "STUDENT");

        insertSessionReport();
        insertSection("상태 관리 개요", 0L, 519_000L);
        insertSection("Context 리렌더링", 520_000L, 921_000L);
        insertChat("리렌더링이 왜 일어나나요?", 610_000L);
        insertChat("감사합니다", 900_000L);
        insertHandRaised(700_000L);
        insertNote();
        for (int index = 1; index <= TIP_HISTORY_COUNT; index++) {
            insertCoachingHistory("E2E-TRIGGER-" + index, index * 300);
        }
    }

    @Test
    @DisplayName("팁 이력 3건 세션을 분석하면 인사이트마다 제목·근거·제안·구간이 있다")
    void every_insight_carries_its_title_evidence_suggestion_and_range() {
        assertThat(useCase.analyze(new AnalyzeSessionInstructorCommand(sessionId))
                        .outcome())
                .isEqualTo(InstructorAnalysisOutcome.ANALYZED);

        List<Map<String, Object>> insights = jdbcTemplate.queryForList(
                "SELECT title, content, suggestion, started_offset_ms, ended_offset_ms"
                        + " FROM instructor_report_insights i"
                        + " JOIN instructor_reports r ON r.id = i.instructor_report_id"
                        + " WHERE r.session_id = ? ORDER BY i.id ASC",
                sessionId);

        assertThat(insights).isNotEmpty();
        assertThat(insights).allSatisfy(row -> {
            assertThat((String) row.get("title")).isNotBlank();
            assertThat((String) row.get("content")).isNotBlank();
            assertThat((String) row.get("suggestion")).isNotBlank();
            // 구간은 둘 다 있거나 둘 다 없다. 둘 다 없으면 전체 수업 대상이다.
            assertThat(row.get("started_offset_ms") == null).isEqualTo(row.get("ended_offset_ms") == null);
        });
        assertThat(insights)
                .anySatisfy(row -> assertThat(row.get("started_offset_ms")).isNotNull());
        assertThat(insights)
                .anySatisfy(row -> assertThat(row.get("started_offset_ms")).isNull());
    }

    @Test
    @DisplayName("종합 피드백·질문 수·분야별 평가 4행이 함께 적재된다")
    void stores_the_feedback_question_count_and_all_four_scores() {
        useCase.analyze(new AnalyzeSessionInstructorCommand(sessionId));

        Map<String, Object> report = jdbcTemplate.queryForMap(
                "SELECT overall_feedback, question_count, published_at FROM instructor_reports WHERE session_id = ?",
                sessionId);

        assertThat((String) report.get("overall_feedback")).isNotBlank();
        assertThat(report.get("question_count")).isNotNull();
        assertThat(report.get("published_at")).isNull();
        assertThat(jdbcTemplate.queryForList(
                        "SELECT evaluation_type FROM instructor_report_scores s"
                                + " JOIN instructor_reports r ON r.id = s.instructor_report_id"
                                + " WHERE r.session_id = ?",
                        String.class,
                        sessionId))
                .containsExactlyInAnyOrder("DELIVERY", "STRUCTURE_FLOW", "INTERACTION", "DIFFICULTY_CONTROL");
    }

    @Test
    @DisplayName("GMS 로 나갈 본문에 학생 이름·이메일·내부 식별자가 없다")
    void the_outgoing_request_carries_no_personal_data() {
        useCase.analyze(new AnalyzeSessionInstructorCommand(sessionId));

        assertThat(recordingPort.captured()).hasSize(1);
        String body = objectMapper.writeValueAsString(
                recordingPort.captured().getFirst().dataPayload());

        assertThat(body)
                .doesNotContain(STUDENT_NAME)
                .doesNotContain(STUDENT_EMAIL_LOCAL)
                .doesNotContain("@")
                .doesNotContain(String.valueOf(sessionId))
                .doesNotContain(String.valueOf(studentParticipantId))
                .doesNotContain(String.valueOf(instructorParticipantId))
                .doesNotContain("sessionId")
                .doesNotContain("participantId");
    }

    @Test
    @DisplayName("두 번 부르면 두 번째는 건너뛰고 행이 늘지 않는다")
    void a_second_run_is_skipped() {
        useCase.analyze(new AnalyzeSessionInstructorCommand(sessionId));
        int insightsAfterFirst = insightRowCount();

        assertThat(useCase.analyze(new AnalyzeSessionInstructorCommand(sessionId))
                        .outcome())
                .isEqualTo(InstructorAnalysisOutcome.SKIPPED);
        assertThat(reportRowCount()).isEqualTo(1);
        assertThat(insightRowCount()).isEqualTo(insightsAfterFirst);
        // 두 번째 실행은 LLM 을 부르지 않는다 — 멱등의 바깥 겹이 먼저 걸린다.
        assertThat(recordingPort.captured()).hasSize(1);
    }

    private int reportRowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM instructor_reports WHERE session_id = ?", Integer.class, sessionId);
    }

    private int insightRowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM instructor_report_insights i"
                        + " JOIN instructor_reports r ON r.id = i.instructor_report_id"
                        + " WHERE r.session_id = ?",
                Integer.class,
                sessionId);
    }

    private long insertMember(String displayName, String emailLocalPart) {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                id,
                "google-" + id,
                emailLocalPart + "@example.com",
                displayName);
        return id;
    }

    private long insertSession(long hostMemberId) {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at)"
                        + " VALUES (?, ?, '상태 관리 수업', ?, 'ENDED', 'PROCESSING', UTC_TIMESTAMP(6),"
                        + " UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                id,
                hostMemberId,
                inviteCode(id));
        return id;
    }

    /** invite_code 는 CHAR(8) 유니크다. TSID 뒷자리를 36진수로 접어 충돌을 피한다. */
    private static String inviteCode(long id) {
        String encoded = Long.toString(Math.abs(id), 36).toUpperCase();
        return encoded.length() <= 8 ? encoded : encoded.substring(encoded.length() - 8);
    }

    private long insertParticipant(long memberId, String role) {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),"
                        + " UTC_TIMESTAMP(6))",
                id,
                sessionId,
                memberId,
                role);
        return id;
    }

    private void insertSessionReport() {
        jdbcTemplate.update(
                "INSERT INTO session_reports (id, session_id, summary, created_at, updated_at)"
                        + " VALUES (?, ?, '지역 상태에서 시작해 Context 와 외부 라이브러리로 이어졌습니다.',"
                        + " UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId);
    }

    private void insertSection(String title, long startedOffsetMs, long endedOffsetMs) {
        jdbcTemplate.update(
                "INSERT INTO session_sections (id, session_id, title, summary, started_offset_ms,"
                        + " ended_offset_ms, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId,
                title,
                title + " 구간 요약",
                startedOffsetMs,
                endedOffsetMs);
    }

    private void insertChat(String content, long occurredOffsetMs) {
        jdbcTemplate.update(
                "INSERT INTO chat_messages (id, session_id, sender_participant_id, channel_type, content,"
                        + " occurred_offset_ms, created_at)"
                        + " VALUES (?, ?, ?, 'PUBLIC', ?, ?, UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId,
                studentParticipantId,
                content,
                occurredOffsetMs);
    }

    private void insertHandRaised(long occurredOffsetMs) {
        jdbcTemplate.update(
                "INSERT INTO interaction_events (id, session_id, actor_participant_id, event_type,"
                        + " occurred_offset_ms, payload, created_at)"
                        + " VALUES (?, ?, ?, 'HAND_RAISED', ?, JSON_OBJECT(), UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId,
                studentParticipantId,
                occurredOffsetMs);
    }

    private void insertNote() {
        jdbcTemplate.update(
                "INSERT INTO instructor_notes (id, session_id, instructor_participant_id, content, status,"
                        + " finalized_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'Context 리렌더링 설명이 급했다.', 'FINALIZED', UTC_TIMESTAMP(6),"
                        + " UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId,
                instructorParticipantId);
    }

    private void insertCoachingHistory(String triggerId, int offsetSeconds) {
        jdbcTemplate.update(
                "INSERT INTO coaching_histories (id, session_id, trigger_id, triggered_at, completed_at,"
                        + " denominator_count, selected_tip_type, outcome_status, transcript_status, topic,"
                        + " tip_type, tip_title, tip_message, created_at)"
                        + " SELECT ?, s.id, ?, DATE_ADD(s.started_at, INTERVAL ? SECOND),"
                        + " DATE_ADD(s.started_at, INTERVAL ? SECOND), 30, 'CONFUSED', 'TIP_DELIVERED',"
                        + " 'TRANSCRIBED', 'Context 리렌더링', 'CONFUSED', '이해 확인 필요', '팁 본문', UTC_TIMESTAMP(6)"
                        + " FROM sessions s WHERE s.id = ?",
                TsidGenerator.generate(),
                triggerId,
                offsetSeconds,
                offsetSeconds + 2,
                sessionId);
    }

    /**
     * mock 어댑터 앞에 끼우는 기록용 대역. GMS 로 나갈 요청을 그대로 붙잡아 개인정보 부재를 검사할 수 있게 한다.
     *
     * <p>{@code @Primary} 라 서비스가 이 빈을 주입받는다. mock 어댑터는 그대로 남아 응답을 만든다.
     */
    static class RecordingAnalysisPort implements InstructorAnalysisPort {

        private final InstructorAnalysisPort delegate;
        private final List<InstructorAnalysisRequest> captured = new ArrayList<>();

        RecordingAnalysisPort(InstructorAnalysisPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<InstructorAnalysis> analyze(InstructorAnalysisRequest request) {
            captured.add(request);
            return delegate.analyze(request);
        }

        List<InstructorAnalysisRequest> captured() {
            return captured;
        }

        void clear() {
            captured.clear();
        }
    }

    @TestConfiguration
    static class RecordingPortConfig {

        @Bean
        @Primary
        RecordingAnalysisPort recordingAnalysisPort(GmsInstructorAnalysisMockAdapter mockAdapter) {
            return new RecordingAnalysisPort(mockAdapter);
        }
    }
}
