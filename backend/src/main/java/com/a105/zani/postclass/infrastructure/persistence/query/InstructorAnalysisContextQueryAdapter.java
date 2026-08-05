package com.a105.zani.postclass.infrastructure.persistence.query;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.postclass.application.analyzeinstructor.ConceptSection;
import com.a105.zani.postclass.application.analyzeinstructor.DeliveredTip;
import com.a105.zani.postclass.application.analyzeinstructor.GroupAlert;
import com.a105.zani.postclass.application.analyzeinstructor.InstructorAnalysisContext;
import com.a105.zani.postclass.application.analyzeinstructor.InstructorAnalysisContextQueryPort;
import com.a105.zani.postclass.application.analyzeinstructor.PublicChat;

/**
 * 강사 분석의 입력을 읽는다. 읽는 테이블 중 하나도 {@code postclass} 소유가 아니라 네이티브 SQL 로만 읽고 쓰지 않는다 —
 * {@code NotificationDiscoveryJpaRepository} 와 같은 방식이다.
 *
 * <p>네이티브 쿼리는 소프트 삭제 필터를 우회하므로 {@code sessions.deleted_at IS NULL} 을 명시한다. 나머지 테이블에는 소프트 삭제 컬럼이 없다.
 *
 * <p>컴파일 시점 검사가 없다. 컬럼명 오타와 스키마 변경은 {@code InstructorAnalysisContextQueryAdapterTest} 에서만 드러난다.
 */
@Component
@RequiredArgsConstructor
public class InstructorAnalysisContextQueryAdapter implements InstructorAnalysisContextQueryPort {

    private static final String COUNT_REPORT = """
            SELECT COUNT(*)
              FROM instructor_reports
             WHERE session_id = ?
            """;

    private static final String SELECT_HEADER = """
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
     * 알림 한 건에 응답 분포를 조건부 집계로 붙인다. 조인만 하면 알림 하나가 응답 유형 수만큼의 행으로 늘어나 자바에서 다시 접어야 한다.
     *
     * <p>{@code LEFT JOIN} 이라 분포가 없는 알림도 남고, 없는 유형은 0 이 된다.
     */
    private static final String SELECT_ALERTS = """
            SELECT ga.occurred_offset_ms,
                   ga.alert_type,
                   ga.numerator_count,
                   ga.denominator_count,
                   COALESCE(SUM(CASE WHEN c.response_type = 'OK' THEN c.response_count END), 0) AS ok_count,
                   COALESCE(SUM(CASE WHEN c.response_type = 'CONFUSED' THEN c.response_count END), 0)
                       AS confused_count,
                   COALESCE(SUM(CASE WHEN c.response_type = 'MISSED' THEN c.response_count END), 0)
                       AS missed_count,
                   COALESCE(SUM(CASE WHEN c.response_type = 'NON_RESPONSE' THEN c.response_count END), 0)
                       AS no_response_count
              FROM group_alerts ga
              LEFT JOIN group_alert_response_counts c ON c.group_alert_id = ga.id
             WHERE ga.session_id = ?
             GROUP BY ga.id, ga.occurred_offset_ms, ga.alert_type, ga.numerator_count, ga.denominator_count
             ORDER BY ga.occurred_offset_ms ASC
            """;

    private static final String SELECT_HAND_RAISED = """
            SELECT occurred_offset_ms
              FROM interaction_events
             WHERE session_id = ?
               AND event_type = 'HAND_RAISED'
             ORDER BY occurred_offset_ms ASC
            """;

    private static final String SELECT_PUBLIC_CHATS = """
            SELECT occurred_offset_ms, content
              FROM chat_messages
             WHERE session_id = ?
               AND channel_type = 'PUBLIC'
             ORDER BY occurred_offset_ms ASC, id ASC
            """;

    /** 팁 이력은 절대 시각만 갖고 있다. 세션 시작을 기준으로 오프셋으로 되돌려야 구간과 맞출 수 있다. */
    private static final String SELECT_TIPS = """
            SELECT TIMESTAMPDIFF(MICROSECOND, s.started_at, ch.triggered_at) DIV 1000 AS triggered_offset_ms,
                   ch.tip_type,
                   ch.tip_title,
                   ch.topic,
                   ch.outcome_status
              FROM coaching_histories ch
              JOIN sessions s ON s.id = ch.session_id
             WHERE ch.session_id = ?
             ORDER BY ch.triggered_at ASC
            """;

    private static final String SELECT_FINALIZED_NOTE = """
            SELECT content
              FROM instructor_notes
             WHERE session_id = ?
               AND status = 'FINALIZED'
             ORDER BY finalized_at DESC, id DESC
             LIMIT 1
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean hasReport(Long sessionId) {
        Integer count = jdbcTemplate.queryForObject(COUNT_REPORT, Integer.class, sessionId);
        return count != null && count > 0;
    }

    @Override
    public Optional<InstructorAnalysisContext> findContext(Long sessionId) {
        List<String[]> header = jdbcTemplate.query(
                SELECT_HEADER,
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
        List<GroupAlert> alerts = jdbcTemplate.query(
                SELECT_ALERTS,
                (rs, rowNum) -> new GroupAlert(
                        rs.getLong("occurred_offset_ms"),
                        rs.getString("alert_type"),
                        rs.getInt("numerator_count"),
                        rs.getInt("denominator_count"),
                        rs.getInt("ok_count"),
                        rs.getInt("confused_count"),
                        rs.getInt("missed_count"),
                        rs.getInt("no_response_count")),
                sessionId);
        List<Long> handRaised = jdbcTemplate.queryForList(SELECT_HAND_RAISED, Long.class, sessionId);
        List<DeliveredTip> tips = jdbcTemplate.query(
                SELECT_TIPS,
                (rs, rowNum) -> new DeliveredTip(
                        rs.getLong("triggered_offset_ms"),
                        rs.getString("tip_type"),
                        rs.getString("tip_title"),
                        rs.getString("topic"),
                        rs.getString("outcome_status")),
                sessionId);
        List<PublicChat> chats = jdbcTemplate.query(
                SELECT_PUBLIC_CHATS,
                (rs, rowNum) -> new PublicChat(rs.getLong("occurred_offset_ms"), rs.getString("content")),
                sessionId);
        return Optional.of(new InstructorAnalysisContext(
                header.getFirst()[0],
                header.getFirst()[1],
                sections,
                alerts,
                handRaised,
                tips,
                chats,
                finalizedNote(sessionId)));
    }

    /** 확정된 메모만 읽는다. 초안은 강사가 아직 고치는 중이고, 확정 뒤에 분석이 시작되는 것이 파이프라인 순서다(AI-001). */
    private String finalizedNote(Long sessionId) {
        List<String> notes = jdbcTemplate.queryForList(SELECT_FINALIZED_NOTE, String.class, sessionId);
        return notes.isEmpty() ? null : notes.getFirst();
    }
}
