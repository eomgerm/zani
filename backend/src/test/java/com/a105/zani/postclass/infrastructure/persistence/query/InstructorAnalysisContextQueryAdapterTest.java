package com.a105.zani.postclass.infrastructure.persistence.query;

import java.util.List;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.postclass.application.analyzeinstructor.ConceptSection;
import com.a105.zani.postclass.application.analyzeinstructor.DeliveredTip;
import com.a105.zani.postclass.application.analyzeinstructor.GroupAlert;
import com.a105.zani.postclass.application.analyzeinstructor.InstructorAnalysisContext;
import com.a105.zani.postclass.application.analyzeinstructor.InstructorAnalysisContextQueryPort;
import com.a105.zani.postclass.application.analyzeinstructor.PublicChat;

import static org.assertj.core.api.Assertions.assertThat;

/** 네이티브 SQL 이라 컴파일 시점 검사가 없다. 컬럼명 오타와 스키마 변경은 이 테스트에서만 드러난다. 로컬 MySQL 이 떠 있어야 통과한다. */
@SpringBootTest
@Transactional
class InstructorAnalysisContextQueryAdapterTest {

    @Autowired
    private InstructorAnalysisContextQueryPort queryPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long sessionId;
    private long instructorParticipantId;
    private long studentParticipantId;

    @BeforeEach
    void setUp() {
        long hostMemberId = insertMember();
        sessionId = insertSession(hostMemberId, "상태 관리 수업");
        instructorParticipantId = insertParticipant(sessionId, hostMemberId, "INSTRUCTOR");
        studentParticipantId = insertParticipant(sessionId, insertMember(), "STUDENT");
    }

    @Test
    @DisplayName("공통 분석이 없는 세션은 빈 값이다 — 오류가 아니라 순서 문제다")
    void returns_empty_when_the_common_analysis_is_missing() {
        assertThat(queryPort.findContext(sessionId)).isEmpty();
    }

    @Test
    @DisplayName("소프트 삭제된 세션은 읽지 않는다 — 네이티브 쿼리는 삭제 필터를 우회한다")
    void skips_a_soft_deleted_session() {
        insertSessionReport("수업 공통 요약");
        jdbcTemplate.update("UPDATE sessions SET deleted_at = UTC_TIMESTAMP(6) WHERE id = ?", sessionId);

        assertThat(queryPort.findContext(sessionId)).isEmpty();
    }

    @Test
    @DisplayName("제목·공통 요약과 1부터 번호 매긴 구간을 준다")
    void returns_the_title_summary_and_sections_numbered_from_one() {
        insertSessionReport("수업 공통 요약");
        insertSection("Context 리렌더링", 520_000L, 921_000L);
        insertSection("상태 관리 개요", 0L, 519_000L);

        InstructorAnalysisContext context = queryPort.findContext(sessionId).orElseThrow();

        assertThat(context.lectureTitle()).isEqualTo("상태 관리 수업");
        assertThat(context.classSummary()).isEqualTo("수업 공통 요약");
        assertThat(context.sections())
                .extracting(ConceptSection::sectionIndex, ConceptSection::title, ConceptSection::startedOffsetMs)
                .containsExactly(Tuple.tuple(1, "상태 관리 개요", 0L), Tuple.tuple(2, "Context 리렌더링", 520_000L));
    }

    @Test
    @DisplayName("집단 알림에 응답 분포를 붙여 준다 — 없는 유형은 0 이다")
    void attaches_the_response_distribution_to_each_alert() {
        insertSessionReport("요약");
        long alertId = insertGroupAlert("CONFUSED", 600_000L, 9, 30);
        insertAlertResponseCount(alertId, "CONFUSED", 7);
        insertAlertResponseCount(alertId, "OK", 21);
        insertAlertResponseCount(alertId, "NON_RESPONSE", 2);

        List<GroupAlert> alerts = queryPort.findContext(sessionId).orElseThrow().alerts();

        assertThat(alerts)
                .extracting(
                        GroupAlert::occurredOffsetMs,
                        GroupAlert::alertType,
                        GroupAlert::numeratorCount,
                        GroupAlert::denominatorCount,
                        GroupAlert::okCount,
                        GroupAlert::confusedCount,
                        GroupAlert::missedCount,
                        GroupAlert::noResponseCount)
                .containsExactly(Tuple.tuple(600_000L, "CONFUSED", 9, 30, 21, 7, 0, 2));
    }

    @Test
    @DisplayName("응답 분포가 없는 알림도 남는다")
    void keeps_an_alert_without_any_distribution() {
        insertSessionReport("요약");
        insertGroupAlert("NON_RESPONSE", 900_000L, 5, 20);

        assertThat(queryPort.findContext(sessionId).orElseThrow().alerts())
                .extracting(GroupAlert::alertType, GroupAlert::okCount)
                .containsExactly(Tuple.tuple("NON_RESPONSE", 0));
    }

    @Test
    @DisplayName("공개 채팅은 발신자 없이 시각과 내용만 준다")
    void returns_public_chats_without_the_sender() {
        insertSessionReport("요약");
        insertChat("PUBLIC", "리렌더링이 왜 일어나나요?", 610_000L);
        insertChat("PRIVATE", "비공개 메시지", 620_000L);

        assertThat(queryPort.findContext(sessionId).orElseThrow().chats())
                .containsExactly(new PublicChat(610_000L, "리렌더링이 왜 일어나나요?"));
    }

    @Test
    @DisplayName("손들기는 누가 했는지 없이 시각만 준다")
    void returns_hand_raised_offsets_only() {
        insertSessionReport("요약");
        insertInteraction("HAND_RAISED", 700_000L);
        insertInteraction("HAND_RAISED", 650_000L);
        insertInteraction("HAND_LOWERED", 660_000L);

        assertThat(queryPort.findContext(sessionId).orElseThrow().handRaisedOffsetsMs())
                .containsExactly(650_000L, 700_000L);
    }

    @Test
    @DisplayName("팁 이력의 절대 시각을 세션 시작 기준 오프셋으로 되돌린다")
    void converts_tip_timestamps_into_offsets() {
        insertSessionReport("요약");
        insertCoachingHistory("TRIGGER-1", 300, "CONFUSED", "이해 확인 필요", "Context 리렌더링");

        assertThat(queryPort.findContext(sessionId).orElseThrow().tips())
                .extracting(DeliveredTip::triggeredOffsetMs, DeliveredTip::tipType, DeliveredTip::title)
                .containsExactly(Tuple.tuple(300_000L, "CONFUSED", "이해 확인 필요"));
    }

    @Test
    @DisplayName("확정된 메모만 읽는다 — 초안은 아직 강사가 고치는 중이다")
    void reads_only_the_finalized_note() {
        insertSessionReport("요약");
        insertNote("초안 메모", "DRAFT");

        assertThat(queryPort.findContext(sessionId).orElseThrow().instructorNote())
                .isNull();

        jdbcTemplate.update(
                "UPDATE instructor_notes SET status = 'FINALIZED', finalized_at = UTC_TIMESTAMP(6)"
                        + " WHERE session_id = ?",
                sessionId);

        assertThat(queryPort.findContext(sessionId).orElseThrow().instructorNote())
                .isEqualTo("초안 메모");
    }

    @Test
    @DisplayName("팁이 한 번도 발동하지 않은 수업도 컨텍스트를 준다 — 목록만 비어 있다")
    void a_session_without_alerts_still_has_a_context() {
        insertSessionReport("요약");
        insertSection("상태 관리 개요", 0L, 519_000L);

        InstructorAnalysisContext context = queryPort.findContext(sessionId).orElseThrow();

        assertThat(context.alerts()).isEmpty();
        assertThat(context.tips()).isEmpty();
        assertThat(context.chats()).isEmpty();
        assertThat(context.sections()).hasSize(1);
    }

    @Test
    @DisplayName("이미 강사 리포트가 있으면 hasReport 가 true 다 — LLM 을 부르기 전에 걸러 낸다")
    void reports_whether_a_report_already_exists() {
        assertThat(queryPort.hasReport(sessionId)).isFalse();

        jdbcTemplate.update(
                "INSERT INTO instructor_reports (id, session_id, overall_feedback, created_at, updated_at)"
                        + " VALUES (?, ?, '이미 있는 피드백', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId);

        assertThat(queryPort.hasReport(sessionId)).isTrue();
    }

    private long insertMember() {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, '강사 분석 조회 테스트', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                id,
                "google-" + id,
                id + "@example.com");
        return id;
    }

    private long insertSession(long hostMemberId, String title) {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'ENDED', 'PROCESSING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),"
                        + " UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                id,
                hostMemberId,
                title,
                inviteCode(id));
        return id;
    }

    /** invite_code 는 CHAR(8) 유니크다. TSID 뒷자리를 36진수로 접어 충돌을 피한다. */
    private static String inviteCode(long id) {
        String encoded = Long.toString(Math.abs(id), 36).toUpperCase();
        return encoded.length() <= 8 ? encoded : encoded.substring(encoded.length() - 8);
    }

    private long insertParticipant(long sessionId, long memberId, String role) {
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

    private void insertSessionReport(String summary) {
        jdbcTemplate.update(
                "INSERT INTO session_reports (id, session_id, summary, created_at, updated_at)"
                        + " VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId,
                summary);
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

    private long insertGroupAlert(String alertType, long occurredOffsetMs, int numerator, int denominator) {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO group_alerts (id, session_id, alert_type, window_started_offset_ms,"
                        + " window_ended_offset_ms, numerator_count, denominator_count, occurred_offset_ms,"
                        + " created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))",
                id,
                sessionId,
                alertType,
                occurredOffsetMs - 300_000L,
                occurredOffsetMs,
                numerator,
                denominator,
                occurredOffsetMs);
        return id;
    }

    private void insertAlertResponseCount(long groupAlertId, String responseType, int count) {
        jdbcTemplate.update(
                "INSERT INTO group_alert_response_counts (id, group_alert_id, response_type, response_count,"
                        + " created_at)"
                        + " VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                groupAlertId,
                responseType,
                count);
    }

    private void insertChat(String channelType, String content, long occurredOffsetMs) {
        jdbcTemplate.update(
                "INSERT INTO chat_messages (id, session_id, sender_participant_id, channel_type, content,"
                        + " occurred_offset_ms, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId,
                studentParticipantId,
                channelType,
                content,
                occurredOffsetMs);
    }

    private void insertInteraction(String eventType, long occurredOffsetMs) {
        jdbcTemplate.update(
                "INSERT INTO interaction_events (id, session_id, actor_participant_id, event_type,"
                        + " occurred_offset_ms, payload, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, JSON_OBJECT(), UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId,
                studentParticipantId,
                eventType,
                occurredOffsetMs);
    }

    private void insertCoachingHistory(
            String triggerId, int offsetSeconds, String tipType, String tipTitle, String topic) {
        jdbcTemplate.update(
                "INSERT INTO coaching_histories (id, session_id, trigger_id, triggered_at, completed_at,"
                        + " denominator_count, selected_tip_type, outcome_status, transcript_status, topic,"
                        + " tip_type, tip_title, tip_message, created_at)"
                        + " SELECT ?, s.id, ?, DATE_ADD(s.started_at, INTERVAL ? SECOND),"
                        + " DATE_ADD(s.started_at, INTERVAL ? SECOND), 30, ?, 'TIP_DELIVERED', 'TRANSCRIBED',"
                        + " ?, ?, ?, '팁 본문', UTC_TIMESTAMP(6)"
                        + " FROM sessions s WHERE s.id = ?",
                TsidGenerator.generate(),
                triggerId,
                offsetSeconds,
                offsetSeconds + 2,
                tipType,
                topic,
                tipType,
                tipTitle,
                sessionId);
    }

    private void insertNote(String content, String status) {
        jdbcTemplate.update(
                "INSERT INTO instructor_notes (id, session_id, instructor_participant_id, content, status,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId,
                instructorParticipantId,
                content,
                status);
    }
}
