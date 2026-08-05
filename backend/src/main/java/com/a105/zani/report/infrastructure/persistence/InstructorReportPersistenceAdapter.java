package com.a105.zani.report.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.report.domain.model.ClassInsight;
import com.a105.zani.report.domain.model.EvaluationType;
import com.a105.zani.report.domain.model.InstructorReport;
import com.a105.zani.report.domain.repository.InstructorReportRepository;

/**
 * 강사 리포트와 분야별 점수·수업 인사이트를 한 번에 넣는다.
 *
 * <p>멱등은 유니크 제약(UK_INSTRUCTOR_REPORTS_SESSION)에 맡긴다. 넣은 뒤 저장된 ID 를 되읽어 우리가 만든 TSID 가 아니면 다른 실행이 먼저 넣은 것으로 보고 자식 행을 붙이지
 * 않는다. 선례는 {@code CoachingHistoryPersistenceAdapter} 다.
 *
 * <p>인사이트 TSID 를 목록 순서대로 발급한다 — 표시 순서 컬럼이 없어 {@code id} 오름차순이 그 순서를 대신한다.
 *
 * <p>저장 시점에는 {@code published_at} 을 채우지 않는다. 저장과 공개는 분리돼 있고, 공개는 파이프라인의 {@code VALIDATING -> PUBLISHED} 단계가
 * {@link #markPublished} 로 한다.
 */
@Component
@RequiredArgsConstructor
public class InstructorReportPersistenceAdapter implements InstructorReportRepository {

    private static final String INSERT_REPORT = """
            INSERT INTO instructor_reports
                (id, session_id, overall_feedback, question_count, created_at, updated_at)
            VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE id = id
            """;

    private static final String SELECT_REPORT_ID = """
            SELECT id
              FROM instructor_reports
             WHERE session_id = ?
            """;

    private static final String INSERT_SCORE = """
            INSERT INTO instructor_report_scores
                (id, instructor_report_id, evaluation_type, score, created_at, updated_at)
            VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """;

    private static final String INSERT_INSIGHT = """
            INSERT INTO instructor_report_insights
                (id, instructor_report_id, title, content, suggestion,
                 started_offset_ms, ended_offset_ms, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """;

    private static final String COUNT_REPORT = """
            SELECT COUNT(*)
              FROM instructor_reports
             WHERE session_id = ?
            """;

    /** {@code published_at is null} 조건이 있어 이미 공개된 행은 시각이 덮이지 않는다. */
    private static final String MARK_PUBLISHED = """
            UPDATE instructor_reports
               SET published_at = ?, updated_at = ?
             WHERE session_id = ?
               AND published_at IS NULL
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean existsBySessionId(Long sessionId) {
        Integer count = jdbcTemplate.queryForObject(COUNT_REPORT, Integer.class, sessionId);
        return count != null && count > 0;
    }

    @Override
    public Optional<Long> saveIfAbsent(InstructorReport report) {
        long reportId = TsidGenerator.generate();
        jdbcTemplate.update(
                INSERT_REPORT, reportId, report.sessionId(), report.overallFeedback(), report.questionCount());

        Long storedId = jdbcTemplate.queryForObject(SELECT_REPORT_ID, Long.class, report.sessionId());
        if (storedId == null || storedId.longValue() != reportId) {
            return Optional.empty();
        }

        jdbcTemplate.batchUpdate(
                INSERT_SCORE,
                report.scores().entrySet().stream()
                        .map(entry -> scoreRow(reportId, entry))
                        .toList());

        List<Object[]> insightRows = report.insights().stream()
                .map(insight -> insightRow(reportId, insight))
                .toList();
        if (!insightRows.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_INSIGHT, insightRows);
        }
        return Optional.of(reportId);
    }

    /**
     * {@code published_at} 은 시간대 없는 {@code DATETIME(6)} 이고 값은 UTC 로 쓰인다.
     *
     * <p>{@code Timestamp} 로 넘기지 않는 이유: JDBC 가 JVM 기본 시간대로 변환해 KST 만큼 어긋난다. Hibernate 의 {@code jdbc.time_zone=UTC} 는
     * 여기에 적용되지 않는다 — 이 어댑터는 JdbcTemplate 로 직접 쓴다.
     */
    @Override
    public boolean markPublished(Long sessionId, Instant publishedAt) {
        LocalDateTime at = LocalDateTime.ofInstant(publishedAt, ZoneOffset.UTC);
        return jdbcTemplate.update(MARK_PUBLISHED, at, at, sessionId) == 1;
    }

    /** 점수는 유형별 한 행(UK)이고 조회가 유형으로 찾으므로 순회 순서가 필요 없다. */
    private Object[] scoreRow(long reportId, Map.Entry<EvaluationType, Integer> entry) {
        return new Object[] {TsidGenerator.generate(), reportId, entry.getKey().name(), entry.getValue()};
    }

    private Object[] insightRow(long reportId, ClassInsight insight) {
        return new Object[] {
            TsidGenerator.generate(),
            reportId,
            insight.title(),
            insight.content(),
            insight.suggestion(),
            insight.startedOffsetMs(),
            insight.endedOffsetMs()
        };
    }
}
