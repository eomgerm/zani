package com.a105.zani.report.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.report.domain.model.ReviewRecommendation;
import com.a105.zani.report.domain.model.StudentReport;
import com.a105.zani.report.domain.repository.StudentReportRepository;

/**
 * 개인 리포트와 복습 추천을 한 번에 넣는다.
 *
 * <p>멱등은 유니크 제약(UK_STUDENT_REPORTS_SESSION_PARTICIPANT)에 맡긴다. 넣은 뒤 저장된 ID 를 되읽어 우리가 만든 TSID 가 아니면 다른 실행이 먼저 넣은 것으로 보고
 * 추천을 덧붙이지 않는다. 선례는 {@code CoachingHistoryPersistenceAdapter} 다.
 *
 * <p>저장 시점에는 {@code published_at} 을 채우지 않는다. 저장과 공개는 분리돼 있고, 공개는 파이프라인의 {@code VALIDATING -> PUBLISHED} 단계가
 * {@link #markPublished} 로 세션 단위 일괄 처리한다.
 */
@Component
@RequiredArgsConstructor
public class StudentReportPersistenceAdapter implements StudentReportRepository {

    private static final String INSERT_REPORT = """
            INSERT INTO student_reports
                (id, session_id, session_participant_id, participation_summary, question_count,
                 created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE id = id
            """;

    private static final String SELECT_REPORT_ID = """
            SELECT id
              FROM student_reports
             WHERE session_id = ?
               AND session_participant_id = ?
            """;

    /** {@code published_at is null} 조건이 있어 이미 공개된 행은 시각이 덮이지 않는다. */
    private static final String MARK_PUBLISHED = """
            UPDATE student_reports
               SET published_at = ?, updated_at = ?
             WHERE session_id = ?
               AND published_at IS NULL
            """;

    private static final String INSERT_RECOMMENDATION = """
            INSERT INTO review_recommendations
                (id, student_report_id, recommendation_type, title, description,
                 started_offset_ms, ended_offset_ms, priority, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<Long> saveIfAbsent(StudentReport report) {
        long reportId = TsidGenerator.generate();
        jdbcTemplate.update(
                INSERT_REPORT,
                reportId,
                report.sessionId(),
                report.sessionParticipantId(),
                report.participationSummary(),
                report.questionCount());

        Long storedId = jdbcTemplate.queryForObject(
                SELECT_REPORT_ID, Long.class, report.sessionId(), report.sessionParticipantId());
        if (storedId == null || storedId.longValue() != reportId) {
            return Optional.empty();
        }

        List<Object[]> rows = report.recommendations().stream()
                .map(recommendation -> row(reportId, recommendation))
                .toList();
        if (!rows.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_RECOMMENDATION, rows);
        }
        return Optional.of(reportId);
    }

    /**
     * {@code published_at} 은 시간대 없는 {@code DATETIME(6)} 이고 값은 UTC 로 쓰인다.
     *
     * <p>{@code Timestamp} 로 넘기지 않는 이유: JDBC 가 JVM 기본 시간대로 변환해 KST 만큼 어긋난다. Hibernate 의 {@code jdbc.time_zone=UTC} 는
     * 여기에 적용되지 않는다 — 이 어댑터는 JdbcTemplate 로 직접 쓴다. {@code CoachingHistoryPersistenceAdapter} 가 같은 이유로 같은 변환을 한다.
     */
    @Override
    public int markPublished(Long sessionId, Instant publishedAt) {
        LocalDateTime at = LocalDateTime.ofInstant(publishedAt, ZoneOffset.UTC);
        return jdbcTemplate.update(MARK_PUBLISHED, at, at, sessionId);
    }

    private Object[] row(long reportId, ReviewRecommendation recommendation) {
        return new Object[] {
            TsidGenerator.generate(),
            reportId,
            recommendation.type().name(),
            recommendation.title(),
            recommendation.description(),
            recommendation.startedOffsetMs(),
            recommendation.endedOffsetMs(),
            recommendation.priority()
        };
    }
}
