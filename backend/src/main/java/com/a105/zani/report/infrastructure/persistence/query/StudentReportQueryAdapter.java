package com.a105.zani.report.infrastructure.persistence.query;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.report.application.getstudentreport.StudentReportQueryPort;
import com.a105.zani.report.application.getstudentreport.StudentReportView;
import com.a105.zani.report.infrastructure.persistence.entity.ReviewRecommendationJpaEntity;
import com.a105.zani.report.infrastructure.persistence.entity.StudentReportJpaEntity;
import com.a105.zani.report.infrastructure.persistence.repository.ReviewRecommendationJpaRepository;
import com.a105.zani.report.infrastructure.persistence.repository.StudentReportJpaRepository;

@Component
@RequiredArgsConstructor
public class StudentReportQueryAdapter implements StudentReportQueryPort {

    private static final String COUNT_PUBLIC_CHATS = """
            SELECT COUNT(*)
            FROM chat_messages
            WHERE session_id = ?
              AND sender_participant_id = ?
              AND channel_type = 'PUBLIC'
            """;

    private static final String COUNT_PROMPT_RESPONSES = """
            SELECT
              COALESCE(SUM(CASE WHEN response = 'CONFUSED' THEN 1 ELSE 0 END), 0) AS confused_count,
              COALESCE(SUM(CASE WHEN response = 'MISSED' THEN 1 ELSE 0 END), 0) AS missed_count
            FROM check_prompts
            WHERE session_id = ?
              AND session_participant_id = ?
            """;

    private final StudentReportJpaRepository studentReportRepository;
    private final ReviewRecommendationJpaRepository recommendationRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<StudentReportView> findBySessionIdAndParticipantId(long sessionId, long participantId) {
        return studentReportRepository
                .findBySessionIdAndSessionParticipantIdAndPublishedAtIsNotNull(sessionId, participantId)
                .map(report -> toView(report, sessionId, participantId));
    }

    private StudentReportView toView(StudentReportJpaEntity report, long sessionId, long participantId) {
        long publicChatCount = jdbcTemplate.queryForObject(COUNT_PUBLIC_CHATS, Long.class, sessionId, participantId);
        ResponseCounts responseCounts = jdbcTemplate.queryForObject(
                COUNT_PROMPT_RESPONSES,
                (resultSet, rowNumber) ->
                        new ResponseCounts(resultSet.getLong("confused_count"), resultSet.getLong("missed_count")),
                sessionId,
                participantId);
        List<StudentReportView.Recommendation> recommendations =
                recommendationRepository
                        .findTop5ByStudentReportIdOrderByPriorityAscStartedOffsetMsAsc(report.getId())
                        .stream()
                        .map(StudentReportQueryAdapter::toRecommendation)
                        .toList();

        return new StudentReportView(
                publicChatCount,
                responseCounts.confusedCount(),
                responseCounts.missedCount(),
                report.getParticipationSummary(),
                recommendations);
    }

    private static StudentReportView.Recommendation toRecommendation(ReviewRecommendationJpaEntity entity) {
        return new StudentReportView.Recommendation(
                entity.getRecommendationType(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getStartedOffsetMs() / 1000L,
                entity.getEndedOffsetMs() / 1000L,
                entity.getPriority().intValue());
    }

    private record ResponseCounts(long confusedCount, long missedCount) {}
}
