package com.a105.zani.report.infrastructure.persistence.query;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 세션 전사를 행으로 펼친다. 학생 복습 클립과 강사 수업 클립(308), 그리고 리포트 질의응답(259)이 같은 전사를 읽으므로 펼치는 규칙을 한 곳이 소유한다 — 여러 곳에 두면 결측 처리나 정렬이 한쪽만
 * 고쳐진다.
 *
 * <p>소비자가 둘로 갈린다. <b>화면</b>은 실명과 초를 원하고({@link #segments}), <b>GMS 로 나가는 경로</b>는 별칭으로 바꿀 참가자 id 와 ms 를
 * 원한다({@link #segmentsIn}). 두 요구가 반대라 SELECT 목록이 다르지만, {@code JSON_TABLE} 펼치기와 결측 처리는 같은 파일이 소유한다.
 *
 * <p>{@code JSON_TABLE} 로 DB 안에서 펼치는 이유는 정렬과 화자 조인을 한 번에 끝내려는 것이다. 자바로 파싱하면 세그먼트마다 참가자·회원을 다시 조회하거나 전체 참가자 표를 메모리에 올려야
 * 하는데, 세 시간 수업의 전사는 수천 행이라 어느 쪽도 이 조회 하나를 위해 치를 값이 아니다.
 *
 * <p>화자 조인을 {@code LEFT JOIN} 으로 두는 것은 의도다. 참가자 행이 사라진 전사(회원 탈퇴 등)에서 발화 자체를 잃지 않는다 — 이름 없는 한 줄이 남는 편이 낫다.
 *
 * <p>전사 문서의 정본 형태는 {@code postclass} 의 {@code TranscriptDocument} 다. 그 도메인의 저장 형태를 여기서 읽는 것은 사후 산출물을 가로질러 읽는 리포트 조회의 성격
 * 때문이다.
 */
@Component
@RequiredArgsConstructor
class SessionTranscriptQuery {

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

    /**
     * 시간창에 걸치는 세그먼트. {@link #SELECT_TRANSCRIPT} 와 달리 {@code members} 를 조인하지 않는다 — 실명을 GMS 로 보내는 것은 가이드 §9 가 금지한다.
     *
     * <p>겹침 판정은 {@code start < to AND end > from} 이다. 구간이 빈틈없이 이어져 있으므로 창 시작 시각에 정확히 끝나는 세그먼트는 앞 구간의 것이고, 여기서 빠지는 것이
     * 맞다. 끝 시각이 없는 세그먼트는 시작 시각으로 대신해 길이 0 으로 다룬다.
     */
    private static final String SELECT_WINDOW = """
            SELECT segment.start_offset_ms        AS start_offset_ms,
                   segment.end_offset_ms          AS end_offset_ms,
                   segment.session_participant_id AS session_participant_id,
                   segment.segment_text           AS segment_text
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
            WHERE transcript.session_id = ?
              AND segment.start_offset_ms < ?
              AND COALESCE(segment.end_offset_ms, segment.start_offset_ms) > ?
            ORDER BY segment.start_offset_ms, segment.end_offset_ms
            """;

    private final JdbcTemplate jdbcTemplate;

    /** 시작 시각 오름차순의 전사. 저장소는 ms 지만 화면 계약이 초라서 이 경계에서 낮춘다. 전사가 아직 없으면 빈 목록이며 오류가 아니다. */
    List<Segment> segments(long sessionId) {
        return jdbcTemplate.query(
                SELECT_TRANSCRIPT,
                (resultSet, rowNumber) -> {
                    long startOffsetMs = resultSet.getLong("start_offset_ms");
                    // 끝 시각이 없는 세그먼트는 시작 시각으로 둔다. 0 으로 두면 길이가 음수인 구간이 생긴다.
                    long endOffsetMs = resultSet.getObject("end_offset_ms") == null
                            ? startOffsetMs
                            : resultSet.getLong("end_offset_ms");
                    return new Segment(
                            startOffsetMs / 1000L,
                            endOffsetMs / 1000L,
                            resultSet.getString("speaker_name"),
                            resultSet.getString("segment_text"));
                },
                sessionId);
    }

    /**
     * 시간창에 걸치는 발화. 시작 시각 오름차순이며, 전사가 없거나 그 시간대에 발화가 없으면 빈 목록이다(오류가 아니다).
     *
     * <p>{@link #segments} 와 갈라지는 지점이 셋이다. 실명을 붙이지 않고(GMS 가이드 §9), ms 를 초로 낮추지 않으며(인용 검증이 ms 로 판정한다), 세션 전체가 아니라 창에 걸치는
     * 것만 고른다. 그럼에도 같은 클래스에 두는 것은 펼치기 규칙을 나누지 않기 위해서다.
     */
    List<WindowSegment> segmentsIn(long sessionId, long fromMs, long toMs) {
        return jdbcTemplate.query(
                SELECT_WINDOW,
                (resultSet, rowNumber) -> {
                    long startOffsetMs = resultSet.getLong("start_offset_ms");
                    long endOffsetMs = resultSet.getObject("end_offset_ms") == null
                            ? startOffsetMs
                            : resultSet.getLong("end_offset_ms");
                    long participantId = resultSet.getLong("session_participant_id");
                    // wasNull 은 직전 getter 를 가리킨다. 아래 getString 뒤로 미루면 결과가 덮여, 화자 없는
                    // 세그먼트가 참가자 0 번으로 둔갑하고 별칭 표에서 조용히 unknown 이 아닌 값을 받는다.
                    Long speaker = resultSet.wasNull() ? null : participantId;
                    return new WindowSegment(startOffsetMs, endOffsetMs, speaker, resultSet.getString("segment_text"));
                },
                sessionId,
                toMs,
                fromMs);
    }

    record Segment(long startSeconds, long endSeconds, String speakerName, String text) {}

    /** 창 안의 발화 한 줄. 화자는 별칭 치환 전 원본이다 — 바꾸는 것은 GMS 로 나가는 경계의 일이다. */
    record WindowSegment(long startOffsetMs, long endOffsetMs, Long sessionParticipantId, String text) {}
}
