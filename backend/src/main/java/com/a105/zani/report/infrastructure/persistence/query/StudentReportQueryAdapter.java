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
import com.a105.zani.report.infrastructure.persistence.repository.SessionReportJpaRepository;
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
    private final SessionReportJpaRepository sessionReportRepository;
    private final JdbcTemplate jdbcTemplate;
    // 전사 펼치기는 강사 수업 클립(308)과 공유한다. 규칙은 SessionTranscriptQuery 가 소유한다.
    private final SessionTranscriptQuery transcriptQuery;

    /** 강사 리포트·수업 클립과 같은 조회를 쓴다. 공개를 판정하는 값은 하나여야 한다. */
    @Override
    public boolean sessionReportPublished(long sessionId) {
        return sessionReportRepository.existsBySessionIdAndPublishedAtIsNotNull(sessionId);
    }

    @Override
    public Optional<StudentReportView> findBySessionIdAndParticipantId(long sessionId, long participantId) {
        return studentReportRepository
                .findBySessionIdAndSessionParticipantId(sessionId, participantId)
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
                // 세지 않고 저장된 판정을 그대로 읽는다. 없으면 null 이며 0 으로 낮추지 않는다.
                report.getQuestionCount(),
                report.getParticipationSummary(),
                recommendations,
                transcript(sessionId));
    }

    private List<StudentReportView.TranscriptSegment> transcript(long sessionId) {
        return transcriptQuery.segments(sessionId).stream()
                .map(segment -> new StudentReportView.TranscriptSegment(
                        segment.startSeconds(), segment.endSeconds(), segment.speakerName(), segment.text()))
                .toList();
    }

    private static StudentReportView.Recommendation toRecommendation(ReviewRecommendationJpaEntity entity) {
        return new StudentReportView.Recommendation(
                entity.getId(),
                entity.getRecommendationType(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getStartedOffsetMs() / 1000L,
                entity.getEndedOffsetMs() / 1000L,
                entity.getPriority().intValue());
    }

    private record ResponseCounts(long confusedCount, long missedCount) {}
}
