package com.a105.zani.report.infrastructure.persistence;

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
 * <p>{@code published_at} 은 채우지 않는다. 저장과 공개는 분리돼 있고, 공개는 파이프라인의 {@code VALIDATING -> PUBLISHED} 단계가 일괄로 한다.
 */
@Component
@RequiredArgsConstructor
public class StudentReportPersistenceAdapter implements StudentReportRepository {

    private static final String INSERT_REPORT = """
            INSERT INTO student_reports
                (id, session_id, session_participant_id, participation_summary, created_at, updated_at)
            VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE id = id
            """;

    private static final String SELECT_REPORT_ID = """
            SELECT id
              FROM student_reports
             WHERE session_id = ?
               AND session_participant_id = ?
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
                report.participationSummary());

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
