package com.a105.zani.postclass.infrastructure.persistence.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.postclass.application.analyzestudents.AnalysisTarget;
import com.a105.zani.postclass.application.analyzestudents.ConceptSection;
import com.a105.zani.postclass.application.analyzestudents.SessionAnalysisContext;
import com.a105.zani.postclass.application.analyzestudents.StudentAnalysisContextQueryPort;
import com.a105.zani.postclass.application.analyzestudents.StudentObservations;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class StudentAnalysisContextQueryAdapterTest {

    @Autowired
    private StudentAnalysisContextQueryPort queryPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long sessionId;
    private long firstStudentId;
    private long secondStudentId;

    @BeforeEach
    void setUp() {
        long hostMemberId = insertMember();
        sessionId = insertSession(hostMemberId, "이차방정식 수업");
        firstStudentId = insertParticipant(sessionId, insertMember(), "STUDENT");
        secondStudentId = insertParticipant(sessionId, insertMember(), "STUDENT");
        insertParticipant(sessionId, hostMemberId, "INSTRUCTOR");
    }

    @Test
    void returnsEmptyContextWhenCommonAnalysisIsMissing() {
        assertThat(queryPort.findSessionContext(sessionId)).isEmpty();
    }

    @Test
    void returnsTitleSummaryAndSectionsNumberedFromOne() {
        insertSessionReport(sessionId, "수업 공통 요약");
        insertSection(sessionId, "인수분해", 600_000L, 900_000L);
        insertSection(sessionId, "근의 공식", 0L, 600_000L);

        Optional<SessionAnalysisContext> context = queryPort.findSessionContext(sessionId);

        assertThat(context).isPresent();
        assertThat(context.get().lectureTitle()).isEqualTo("이차방정식 수업");
        assertThat(context.get().classSummary()).isEqualTo("수업 공통 요약");
        assertThat(context.get().sections())
                .extracting(ConceptSection::sectionIndex, ConceptSection::title, ConceptSection::startedOffsetMs)
                .containsExactly(Tuple.tuple(1, "근의 공식", 0L), Tuple.tuple(2, "인수분해", 600_000L));
    }

    @Test
    void skipsSoftDeletedSession() {
        insertSessionReport(sessionId, "요약");
        jdbcTemplate.update("UPDATE sessions SET deleted_at = UTC_TIMESTAMP(6) WHERE id = ?", sessionId);

        assertThat(queryPort.findSessionContext(sessionId)).isEmpty();
    }

    @Test
    void excludesStudentsWithReportAndKeepsStudentOrderStable() {
        insertStudentReport(sessionId, firstStudentId);

        List<AnalysisTarget> targets = queryPort.findStudentsWithoutReport(sessionId);

        // 순번은 리포트가 있는 학생을 포함한 전체 학생 순서다. 첫 학생이 빠져도 둘째는 계속 2번이어야
        // 재실행에서 같은 학생에게 같은 별칭이 붙는다.
        assertThat(targets)
                .extracting(AnalysisTarget::sessionParticipantId, AnalysisTarget::studentOrder)
                .containsExactly(Tuple.tuple(secondStudentId, 2));
    }

    @Test
    void excludesInstructorFromTargets() {
        List<AnalysisTarget> targets = queryPort.findStudentsWithoutReport(sessionId);

        assertThat(targets)
                .extracting(AnalysisTarget::sessionParticipantId)
                .containsExactly(firstStudentId, secondStudentId);
    }

    @Test
    void readsOnlyTheGivenStudentsObservations() {
        insertAttentionEvent(firstStudentId, "NOT_ENGAGED", 10_000L);
        insertAttentionEvent(firstStudentId, "ENGAGED", 20_000L);
        insertAttentionEvent(secondStudentId, "CAMERA_OFF", 30_000L);
        insertCheckPrompt(firstStudentId, "UNDERSTANDING_CHECK", "CONFUSED", 30_000L);
        insertCheckPrompt(firstStudentId, "POSTURE_GUIDE", "OK", 40_000L);
        insertHandRaised(firstStudentId, 50_000L);
        insertChatMessage(firstStudentId, "PUBLIC", "이 부분 질문이요", 60_000L);
        insertChatMessage(firstStudentId, "PRIVATE", "선생님 개인 메시지", 70_000L);

        StudentObservations observations = queryPort.findObservations(sessionId, firstStudentId);

        assertThat(observations.attentions())
                .extracting(StudentObservations.Attention::detectorOutcome)
                .containsExactly("NOT_ENGAGED", "ENGAGED");
        // 이해 확인만 읽는다. 자세 안내는 학생 이해도의 근거가 아니다.
        assertThat(observations.prompts())
                .extracting(StudentObservations.Prompt::response)
                .containsExactly("CONFUSED");
        assertThat(observations.handRaisedOffsetsMs()).containsExactly(50_000L);
        // 1:1 채팅은 범위 밖이다(106 의 private chat 필터와 같은 규칙).
        assertThat(observations.chats())
                .extracting(StudentObservations.Chat::content)
                .containsExactly("이 부분 질문이요");
    }

    private long insertMember() {
        long memberId = TsidGenerator.generate();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                memberId,
                "analysis-" + suffix,
                "analysis-" + suffix + "@example.com",
                "analysis test");
        return memberId;
    }

    private long insertSession(long hostMemberId, String title) {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status, started_at,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'ENDED', 'PROCESSING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),"
                        + " UTC_TIMESTAMP(6))",
                id,
                hostMemberId,
                title,
                UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        return id;
    }

    private long insertParticipant(long sessionId, long memberId, String role) {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at, created_at,"
                        + " updated_at) VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                id,
                sessionId,
                memberId,
                role);
        return id;
    }

    private void insertSessionReport(long sessionId, String summary) {
        jdbcTemplate.update(
                "INSERT INTO session_reports (id, session_id, summary, created_at, updated_at)"
                        + " VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId,
                summary);
    }

    private void insertSection(long sessionId, String title, long startedMs, long endedMs) {
        jdbcTemplate.update(
                "INSERT INTO session_sections (id, session_id, title, summary, started_offset_ms, ended_offset_ms,"
                        + " created_at, updated_at) VALUES (?, ?, ?, '구간 요약', ?, ?, UTC_TIMESTAMP(6),"
                        + " UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId,
                title,
                startedMs,
                endedMs);
    }

    private void insertStudentReport(long sessionId, long participantId) {
        jdbcTemplate.update(
                "INSERT INTO student_reports (id, session_id, session_participant_id, participation_summary,"
                        + " created_at, updated_at) VALUES (?, ?, ?, '요약', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId,
                participantId);
    }

    private void insertAttentionEvent(long participantId, String detectorOutcome, long occurredMs) {
        jdbcTemplate.update(
                "INSERT INTO attention_events (id, session_id, session_participant_id, detector_outcome,"
                        + " occurred_offset_ms, created_at) VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId,
                participantId,
                detectorOutcome,
                occurredMs);
    }

    private void insertCheckPrompt(long participantId, String triggerType, String response, long shownMs) {
        jdbcTemplate.update(
                "INSERT INTO check_prompts (id, session_id, session_participant_id, trigger_type, status, response,"
                        + " shown_offset_ms, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'RESPONDED', ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId,
                participantId,
                triggerType,
                response,
                shownMs);
    }

    private void insertHandRaised(long participantId, long occurredMs) {
        jdbcTemplate.update(
                "INSERT INTO interaction_events (id, session_id, actor_participant_id, event_type, occurred_offset_ms,"
                        + " payload, created_at) VALUES (?, ?, ?, 'HAND_RAISED', ?, '{}', UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId,
                participantId,
                occurredMs);
    }

    private void insertChatMessage(long participantId, String channelType, String content, long occurredMs) {
        jdbcTemplate.update(
                "INSERT INTO chat_messages (id, session_id, sender_participant_id, channel_type, content,"
                        + " occurred_offset_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId,
                participantId,
                channelType,
                content,
                occurredMs);
    }
}
