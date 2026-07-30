package com.a105.zani.attention.infrastructure.persistence.query;

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

import com.a105.zani.attention.domain.model.DetectorOutcome;
import com.a105.zani.attention.domain.model.PromptAnswer;
import com.a105.zani.attention.domain.model.timeline.ObservationRecord;
import com.a105.zani.attention.domain.model.timeline.PromptRecord;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 타임라인 조회 어댑터의 DB 수준 계약 검증. 계약 이전 행 걸러내기·정렬·프롬프트 기준 시각 대체는 모두 SQL 이 하는 일이라 fake 로 대체하지 않는다. 로컬 MySQL 이 떠 있어야 통과하며, 테스트가
 * 넣은 행은 끝나고 지운다.
 */
@SpringBootTest
class AttentionTimelineQueryAdapterTest {

    private static final long INSTRUCTOR_ID = 9_200_910L;
    private static final long STUDENT_ID = 9_200_911L;
    private static final long OTHER_STUDENT_ID = 9_200_912L;
    private static final long SESSION_ID = 9_200_913L;
    private static final long PARTICIPANT_ID = 9_200_914L;
    private static final long OTHER_PARTICIPANT_ID = 9_200_915L;

    private static long eventId = 9_200_920_000L;

    @Autowired
    private AttentionTimelineQueryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Instant now;

    /** Hibernate 가 UTC 로 저장하므로 직접 INSERT 할 때도 UTC 기준 시각을 넣는다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        cleanUp();
        insertMember(INSTRUCTOR_ID, "타임라인 조회 테스트 강사");
        insertMember(STUDENT_ID, "타임라인 조회 테스트 학생");
        insertMember(OTHER_STUDENT_ID, "타임라인 조회 테스트 학생2");
        insertEndedSession();
        insertParticipant(PARTICIPANT_ID, STUDENT_ID, "STUDENT");
        insertParticipant(OTHER_PARTICIPANT_ID, OTHER_STUDENT_ID, "STUDENT");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM attention_events WHERE session_id = ?", SESSION_ID);
        jdbcTemplate.update("DELETE FROM check_prompts WHERE session_id = ?", SESSION_ID);
        jdbcTemplate.update("DELETE FROM session_participants WHERE session_id = ?", SESSION_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", SESSION_ID);
    }

    @Test
    @DisplayName("detector_outcome 이 비어 있는 계약 이전 행은 건너뛴다")
    void skips_rows_without_detector_outcome() {
        insertEvent(PARTICIPANT_ID, 0L, "ENGAGED");
        insertEvent(PARTICIPANT_ID, 10_000L, null);

        assertThat(adapter.observations(SESSION_ID))
                .containsExactly(new ObservationRecord(PARTICIPANT_ID, 0L, DetectorOutcome.ENGAGED));
    }

    @Test
    @DisplayName("서버가 모르는 detector_outcome 값도 건너뛴다")
    void skips_rows_with_an_unknown_detector_outcome() {
        insertEvent(PARTICIPANT_ID, 0L, "ENGAGED");
        // 검출기 계약이 넓어지면 서버보다 앞선 값이 먼저 저장될 수 있다. 그때 리포트 전체가 죽으면 안 된다.
        insertEvent(PARTICIPANT_ID, 10_000L, "SOMETHING_NEW");

        assertThat(adapter.observations(SESSION_ID))
                .containsExactly(new ObservationRecord(PARTICIPANT_ID, 0L, DetectorOutcome.ENGAGED));
    }

    @Test
    @DisplayName("offset 오름차순으로 돌려준다")
    void returns_rows_in_offset_order() {
        insertEvent(PARTICIPANT_ID, 20_000L, "ENGAGED");
        insertEvent(PARTICIPANT_ID, 0L, "CAMERA_OFF");
        insertEvent(PARTICIPANT_ID, 10_000L, "UNMEASURABLE");

        assertThat(adapter.observations(SESSION_ID))
                .extracting(ObservationRecord::offsetMs)
                .containsExactly(0L, 10_000L, 20_000L);
    }

    @Test
    @DisplayName("학생별 조회는 그 학생의 관측만 읽는다")
    void reads_only_one_participant() {
        insertEvent(PARTICIPANT_ID, 0L, "ENGAGED");
        insertEvent(OTHER_PARTICIPANT_ID, 0L, "ENGAGED");

        assertThat(adapter.observations(SESSION_ID, PARTICIPANT_ID))
                .extracting(ObservationRecord::participantId)
                .containsExactly(PARTICIPANT_ID);
        assertThat(adapter.observations(SESSION_ID)).hasSize(2);
    }

    @Test
    @DisplayName("UNDERSTANDING_CHECK 이 아닌 프롬프트와 응답 없는 행은 제외한다")
    void reads_only_answered_understanding_checks() {
        insertPrompt(PARTICIPANT_ID, "UNDERSTANDING_CHECK", "CONFUSED", 10_000L, 12_000L);
        insertPrompt(PARTICIPANT_ID, "UNDERSTANDING_CHECK", null, 20_000L, null);
        insertPrompt(PARTICIPANT_ID, "CAMERA_GUIDE", "OK", 30_000L, 31_000L);

        assertThat(adapter.prompts(SESSION_ID))
                .containsExactly(new PromptRecord(PARTICIPANT_ID, 12_000L, PromptAnswer.CONFUSED));
    }

    @Test
    @DisplayName("응답 시각이 없으면 표시 시각을 기준 시각으로 쓴다")
    void falls_back_to_shown_offset() {
        // 브라우저가 30초 무응답으로 닫으며 보낸 행에는 응답 시각이 없을 수 있다. 표시 시각이 유일한 근거다.
        insertPrompt(PARTICIPANT_ID, "UNDERSTANDING_CHECK", "NON_RESPONSE", 40_000L, null);

        assertThat(adapter.prompts(SESSION_ID))
                .containsExactly(new PromptRecord(PARTICIPANT_ID, 40_000L, PromptAnswer.NON_RESPONSE));
    }

    @Test
    @DisplayName("학생별 프롬프트 조회는 그 학생의 응답만 읽는다")
    void reads_only_one_participants_prompts() {
        insertPrompt(PARTICIPANT_ID, "UNDERSTANDING_CHECK", "CONFUSED", 10_000L, 12_000L);
        insertPrompt(OTHER_PARTICIPANT_ID, "UNDERSTANDING_CHECK", "MISSED", 10_000L, 12_000L);

        assertThat(adapter.prompts(SESSION_ID, PARTICIPANT_ID))
                .containsExactly(new PromptRecord(PARTICIPANT_ID, 12_000L, PromptAnswer.CONFUSED));
    }

    @Test
    @DisplayName("관측이 한 건도 없으면 빈 목록이다 — 오류가 아니다")
    void an_empty_session_yields_empty_lists() {
        assertThat(adapter.observations(SESSION_ID)).isEmpty();
        assertThat(adapter.prompts(SESSION_ID)).isEmpty();
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

    private void insertEndedSession() {
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'ENDED', 'NOT_STARTED', ?, ?, ?)",
                SESSION_ID,
                INSTRUCTOR_ID,
                "타임라인 조회 테스트",
                "TLQRY910",
                utc(now.minusSeconds(3600)),
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
                utc(now),
                utc(now),
                utc(now),
                utc(now));
    }

    private void insertEvent(long participantId, long offsetMs, String outcome) {
        jdbcTemplate.update(
                "INSERT INTO attention_events (id, session_id, session_participant_id, detector_outcome,"
                        + " occurred_offset_ms, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                eventId++,
                SESSION_ID,
                participantId,
                outcome,
                offsetMs,
                utc(now));
    }

    private void insertPrompt(
            long participantId, String triggerType, String response, long shownOffsetMs, Long respondedOffsetMs) {
        jdbcTemplate.update(
                "INSERT INTO check_prompts (id, session_id, session_participant_id, trigger_type, status,"
                        + " response, shown_offset_ms, responded_offset_ms, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                eventId++,
                SESSION_ID,
                participantId,
                triggerType,
                response == null ? "OPEN" : "RESPONDED",
                response,
                shownOffsetMs,
                respondedOffsetMs,
                utc(now),
                utc(now));
    }
}
