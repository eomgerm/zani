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

    /**
     * 전사 JSON 을 행으로 펼치고 화자를 실명으로 바꾼다.
     *
     * <p>{@code JSON_TABLE} 로 DB 안에서 펼치는 이유는 정렬과 화자 조인을 한 번에 끝내려는 것이다. 자바로 파싱하면 세그먼트마다 참가자·회원을 다시 조회하거나 전체 참가자 표를 메모리에
     * 올려야 하는데, 세 시간 수업의 전사는 수천 행이라 어느 쪽도 이 조회 하나를 위해 치를 값이 아니다.
     *
     * <p>화자 조인을 {@code LEFT JOIN} 으로 두는 것은 의도다. 참가자 행이 사라진 전사(회원 탈퇴 등)에서 발화 자체를 잃지 않는다 — 이름 없는 한 줄이 남는 편이 낫다.
     *
     * <p>전사 문서의 정본 형태는 {@code postclass} 의 {@code TranscriptDocument} 다. 그 도메인의 저장 형태를 여기서 읽는 것은 사후 산출물을 가로질러 읽는 리포트
     * 조회의 성격 때문이고, 같은 이유로 이 어댑터는 이미 {@code chat_messages}·{@code check_prompts} 도 직접 읽는다.
     */
    private static final String SELECT_TRANSCRIPT = """
            SELECT segment.start_offset_ms AS start_offset_ms,
                   segment.end_offset_ms   AS end_offset_ms,
                   speaker.display_name    AS speaker_name,
                   segment.segment_text    AS segment_text
            FROM transcripts transcript,
                 JSON_TABLE(
                     transcript.transcript_document,
                     '$.segments[*]'
                     COLUMNS (
                         session_participant_id BIGINT PATH '$.sessionParticipantId',
                         start_offset_ms BIGINT PATH '$.startOffsetMs',
                         end_offset_ms BIGINT PATH '$.endOffsetMs',
                         segment_text TEXT PATH '$.text'
                     )
                 ) AS segment
                 LEFT JOIN session_participants participant
                        ON participant.id = segment.session_participant_id
                 LEFT JOIN members speaker
                        ON speaker.id = participant.member_id
            WHERE transcript.session_id = ?
            ORDER BY segment.start_offset_ms, segment.end_offset_ms
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
                // 세지 않고 저장된 판정을 그대로 읽는다. 없으면 null 이며 0 으로 낮추지 않는다.
                report.getQuestionCount(),
                report.getParticipationSummary(),
                recommendations,
                transcript(sessionId));
    }

    private List<StudentReportView.TranscriptSegment> transcript(long sessionId) {
        return jdbcTemplate.query(
                SELECT_TRANSCRIPT,
                (resultSet, rowNumber) -> {
                    long startOffsetMs = resultSet.getLong("start_offset_ms");
                    // 끝 시각이 없는 세그먼트는 시작 시각으로 둔다. 0 으로 두면 길이가 음수인 구간이 생긴다.
                    long endOffsetMs = resultSet.getObject("end_offset_ms") == null
                            ? startOffsetMs
                            : resultSet.getLong("end_offset_ms");
                    return new StudentReportView.TranscriptSegment(
                            startOffsetMs / 1000L,
                            endOffsetMs / 1000L,
                            resultSet.getString("speaker_name"),
                            resultSet.getString("segment_text"));
                },
                sessionId);
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
