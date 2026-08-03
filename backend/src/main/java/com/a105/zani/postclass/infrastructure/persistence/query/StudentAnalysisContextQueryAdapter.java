package com.a105.zani.postclass.infrastructure.persistence.query;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.postclass.application.analyzestudents.AnalysisTarget;
import com.a105.zani.postclass.application.analyzestudents.ConceptSection;
import com.a105.zani.postclass.application.analyzestudents.SessionAnalysisContext;
import com.a105.zani.postclass.application.analyzestudents.StudentAnalysisContextQueryPort;
import com.a105.zani.postclass.application.analyzestudents.StudentObservations;

/**
 * 학생별 분석의 입력을 읽는다. 읽는 아홉 테이블 중 하나도 {@code postclass} 소유가 아니라 네이티브 SQL 로만 읽고 쓰지 않는다 —
 * {@code NotificationDiscoveryJpaRepository} 와 같은 방식이다.
 *
 * <p>네이티브 쿼리는 소프트 삭제 필터를 우회하므로 {@code sessions.deleted_at IS NULL} 을 명시한다. 나머지 여덟 테이블에는 소프트 삭제 컬럼이 없다.
 *
 * <p>컴파일 시점 검사가 없다. 컬럼명 오타와 스키마 변경은 {@code StudentAnalysisContextQueryAdapterTest} 에서만 드러난다.
 */
@Component
@RequiredArgsConstructor
public class StudentAnalysisContextQueryAdapter implements StudentAnalysisContextQueryPort {

    private static final String SELECT_CONTEXT = """
            SELECT s.title AS lecture_title, sr.summary AS class_summary
              FROM sessions s
              JOIN session_reports sr ON sr.session_id = s.id
             WHERE s.id = ?
               AND s.deleted_at IS NULL
            """;

    private static final String SELECT_SECTIONS = """
            SELECT title, summary, started_offset_ms, ended_offset_ms
              FROM session_sections
             WHERE session_id = ?
             ORDER BY started_offset_ms ASC, id ASC
            """;

    /**
     * 순번은 리포트 유무와 무관한 전체 학생 순서여야 한다. 상관 서브쿼리로 "나보다 ID 가 작거나 같은 학생 수" 를 센다. 세션당 학생이 수십 명이라 이 비용은 문제가 되지 않고, 목록에서 빠진 학생
     * 때문에 번호가 밀리는 사고를 막는 값이 더 크다.
     */
    private static final String SELECT_TARGETS = """
            SELECT p.id AS session_participant_id,
                   (SELECT COUNT(*)
                      FROM session_participants earlier
                     WHERE earlier.session_id = p.session_id
                       AND earlier.role = 'STUDENT'
                       AND earlier.id <= p.id) AS student_order
              FROM session_participants p
              LEFT JOIN student_reports r
                     ON r.session_id = p.session_id
                    AND r.session_participant_id = p.id
             WHERE p.session_id = ?
               AND p.role = 'STUDENT'
               AND r.id IS NULL
             ORDER BY p.id ASC
            """;

    private static final String SELECT_ATTENTIONS = """
            SELECT detector_outcome, occurred_offset_ms
              FROM attention_events
             WHERE session_id = ?
               AND session_participant_id = ?
             ORDER BY occurred_offset_ms ASC
            """;

    private static final String SELECT_PROMPTS = """
            SELECT response, shown_offset_ms
              FROM check_prompts
             WHERE session_id = ?
               AND session_participant_id = ?
               AND trigger_type = 'UNDERSTANDING_CHECK'
             ORDER BY shown_offset_ms ASC
            """;

    private static final String SELECT_HAND_RAISED = """
            SELECT occurred_offset_ms
              FROM interaction_events
             WHERE session_id = ?
               AND actor_participant_id = ?
               AND event_type = 'HAND_RAISED'
             ORDER BY occurred_offset_ms ASC
            """;

    private static final String SELECT_PUBLIC_CHATS = """
            SELECT content, occurred_offset_ms
              FROM chat_messages
             WHERE session_id = ?
               AND sender_participant_id = ?
               AND channel_type = 'PUBLIC'
             ORDER BY occurred_offset_ms ASC
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<SessionAnalysisContext> findSessionContext(Long sessionId) {
        List<String[]> header = jdbcTemplate.query(
                SELECT_CONTEXT,
                (rs, rowNum) -> new String[] {rs.getString("lecture_title"), rs.getString("class_summary")},
                sessionId);
        if (header.isEmpty()) {
            return Optional.empty();
        }
        AtomicInteger index = new AtomicInteger();
        List<ConceptSection> sections = jdbcTemplate.query(
                SELECT_SECTIONS,
                (rs, rowNum) -> new ConceptSection(
                        index.incrementAndGet(),
                        rs.getString("title"),
                        rs.getString("summary"),
                        rs.getLong("started_offset_ms"),
                        rs.getLong("ended_offset_ms")),
                sessionId);
        return Optional.of(new SessionAnalysisContext(header.getFirst()[0], header.getFirst()[1], sections));
    }

    @Override
    public List<AnalysisTarget> findStudentsWithoutReport(Long sessionId) {
        return jdbcTemplate.query(
                SELECT_TARGETS,
                (rs, rowNum) -> new AnalysisTarget(rs.getLong("session_participant_id"), rs.getInt("student_order")),
                sessionId);
    }

    @Override
    public StudentObservations findObservations(Long sessionId, Long sessionParticipantId) {
        return new StudentObservations(
                jdbcTemplate.query(
                        SELECT_ATTENTIONS,
                        (rs, rowNum) -> new StudentObservations.Attention(
                                rs.getString("detector_outcome"), rs.getLong("occurred_offset_ms")),
                        sessionId,
                        sessionParticipantId),
                jdbcTemplate.query(
                        SELECT_PROMPTS,
                        (rs, rowNum) ->
                                new StudentObservations.Prompt(rs.getString("response"), rs.getLong("shown_offset_ms")),
                        sessionId,
                        sessionParticipantId),
                jdbcTemplate.queryForList(SELECT_HAND_RAISED, Long.class, sessionId, sessionParticipantId),
                jdbcTemplate.query(
                        SELECT_PUBLIC_CHATS,
                        (rs, rowNum) ->
                                new StudentObservations.Chat(rs.getString("content"), rs.getLong("occurred_offset_ms")),
                        sessionId,
                        sessionParticipantId));
    }
}
