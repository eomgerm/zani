package com.a105.zani.report.infrastructure.persistence.query;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 세션 전사를 실명 화자 행으로 펼친다. 학생 복습 클립과 강사 수업 클립(308)이 같은 전사를 읽으므로 펼치는 규칙을 한 곳이 소유한다 — 두 곳에 두면 결측 처리나 정렬이 한쪽만 고쳐진다.
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

    record Segment(long startSeconds, long endSeconds, String speakerName, String text) {}
}
