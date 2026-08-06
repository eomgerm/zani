package com.a105.zani.report.infrastructure.persistence.query;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.report.application.askreportquestion.TranscriptLine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시간창 조회의 경계와 결측 처리를 고정한다.
 *
 * <p>창이 한 구간 밀리거나 화자가 조용히 뒤바뀌어도 답변은 유창하게 나오므로 화면으로는 잡히지 않는다. 여기가 유일한 방어선이다.
 */
@SpringBootTest
@Transactional
class TranscriptWindowQueryAdapterTest {

    private static final long INSTRUCTOR_ID = 9_259_000L;
    private static final long SESSION_ID = 9_259_010L;
    private static final long INSTRUCTOR_PARTICIPANT_ID = 9_259_020L;
    private static final LocalDateTime NOW =
            LocalDateTime.ofInstant(Instant.parse("2026-08-07T01:00:00Z"), ZoneOffset.UTC);

    /** 3번 구간을 짚었을 때의 창(2~4번 구간). 설계 문서의 해시 테이블 예시와 같은 값이다. */
    private static final long WINDOW_FROM_MS = 60_000L;

    private static final long WINDOW_TO_MS = 185_000L;

    @Autowired
    private TranscriptWindowQueryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                INSTRUCTOR_ID,
                "assistant-google-" + INSTRUCTOR_ID,
                INSTRUCTOR_ID + "@report.test",
                "박상은",
                NOW,
                NOW);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at)"
                        + " VALUES (?, ?, '해시 테이블 수업', 'RP259010', 'ENDED', 'COMPLETED', ?, ?, ?, ?)",
                SESSION_ID,
                INSTRUCTOR_ID,
                NOW.minusHours(1),
                NOW,
                NOW,
                NOW);
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at) VALUES (?, ?, ?, 'INSTRUCTOR', ?, ?, ?, ?)",
                INSTRUCTOR_PARTICIPANT_ID,
                SESSION_ID,
                INSTRUCTOR_ID,
                NOW,
                NOW,
                NOW,
                NOW);
    }

    @Test
    @DisplayName("창에 걸치는 발화만 시작 시각순으로 돌려준다")
    void returns_only_the_utterances_overlapping_the_window() {
        insertTranscript("""
                {
                  "schemaVersion": 1,
                  "segments": [
                    {"sessionParticipantId": %d, "startOffsetMs": 150000, "endOffsetMs": 155000, "text": "적재율"},
                    {"sessionParticipantId": %d, "startOffsetMs": 22000, "endOffsetMs": 26000, "text": "다들 들어왔나요"},
                    {"sessionParticipantId": %d, "startOffsetMs": 92000, "endOffsetMs": 97000, "text": "해시 충돌"},
                    {"sessionParticipantId": %d, "startOffsetMs": 190000, "endOffsetMs": 195000, "text": "다음 시간에"},
                    {"sessionParticipantId": %d, "startOffsetMs": 63000, "endOffsetMs": 69000, "text": "해시 테이블"}
                  ]
                }
                """.formatted(
                        INSTRUCTOR_PARTICIPANT_ID,
                        INSTRUCTOR_PARTICIPANT_ID,
                        INSTRUCTOR_PARTICIPANT_ID,
                        INSTRUCTOR_PARTICIPANT_ID,
                        INSTRUCTOR_PARTICIPANT_ID));

        List<TranscriptLine> lines = adapter.findIn(SESSION_ID, WINDOW_FROM_MS, WINDOW_TO_MS);

        // 문서 안의 순서가 아니라 시작 시각순이다. LLM 은 이 순서를 그대로 시간 흐름으로 읽는다.
        assertThat(lines).extracting(TranscriptLine::text).containsExactly("해시 테이블", "해시 충돌", "적재율");
    }

    @Test
    @DisplayName("창 시작에 정확히 끝나거나 창 끝에서 시작하는 발화는 뺀다")
    void excludes_utterances_that_only_touch_the_window_edges() {
        insertTranscript(
                """
                {
                  "schemaVersion": 1,
                  "segments": [
                    {"sessionParticipantId": %d, "startOffsetMs": 55000, "endOffsetMs": 60000, "text": "앞 구간의 끝"},
                    {"sessionParticipantId": %d, "startOffsetMs": 185000, "endOffsetMs": 190000, "text": "뒤 구간의 시작"},
                    {"sessionParticipantId": %d, "startOffsetMs": 59000, "endOffsetMs": 61000, "text": "경계를 물고 있음"}
                  ]
                }
                """.formatted(INSTRUCTOR_PARTICIPANT_ID, INSTRUCTOR_PARTICIPANT_ID, INSTRUCTOR_PARTICIPANT_ID));

        List<TranscriptLine> lines = adapter.findIn(SESSION_ID, WINDOW_FROM_MS, WINDOW_TO_MS);

        // 구간이 빈틈없이 이어지므로 경계에 닿기만 한 발화는 옆 구간의 것이다. 물고 있는 것만 남는다.
        assertThat(lines).extracting(TranscriptLine::text).containsExactly("경계를 물고 있음");
    }

    @Test
    @DisplayName("화자를 알 수 없는 발화도 화자 없이 남긴다")
    void keeps_an_utterance_whose_speaker_is_unknown() {
        insertTranscript("""
                {
                  "schemaVersion": 1,
                  "segments": [
                    {"sessionParticipantId": null, "startOffsetMs": 70000, "endOffsetMs": 72000, "text": "화자 미상"}
                  ]
                }
                """);

        List<TranscriptLine> lines = adapter.findIn(SESSION_ID, WINDOW_FROM_MS, WINDOW_TO_MS);

        // 참가자 행이 사라진 전사에서 발화를 잃지 않는다. 0 으로 떨어지면 별칭 표가 엉뚱한 학생을 가리킨다.
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().sessionParticipantId()).isNull();
        assertThat(lines.getFirst().text()).isEqualTo("화자 미상");
    }

    @Test
    @DisplayName("끝 시각이 없는 발화는 시작 시각으로 채운다")
    void fills_a_missing_end_with_the_start() {
        insertTranscript("""
                {
                  "schemaVersion": 1,
                  "segments": [
                    {"sessionParticipantId": %d, "startOffsetMs": 70000, "text": "끝 시각 없음"}
                  ]
                }
                """.formatted(INSTRUCTOR_PARTICIPANT_ID));

        List<TranscriptLine> lines = adapter.findIn(SESSION_ID, WINDOW_FROM_MS, WINDOW_TO_MS);

        // 0 으로 두면 길이가 음수인 발화가 생겨 인용 스냅이 뒤로 밀린다.
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().startOffsetMs()).isEqualTo(70_000L);
        assertThat(lines.getFirst().endOffsetMs()).isEqualTo(70_000L);
    }

    @Test
    @DisplayName("전사가 아직 없는 세션은 빈 목록이다")
    void returns_empty_when_the_session_has_no_transcript() {
        // 사후 분석 전 세션이다. 오류로 다루면 화면이 "불러오지 못했어요" 를 띄우는데, 실제로는 아직 없을 뿐이다.
        assertThat(adapter.findIn(SESSION_ID, WINDOW_FROM_MS, WINDOW_TO_MS)).isEmpty();
    }

    @Test
    @DisplayName("발화가 없는 시간대는 빈 목록이다")
    void returns_empty_when_nobody_spoke_in_the_window() {
        insertTranscript("""
                {
                  "schemaVersion": 1,
                  "segments": [
                    {"sessionParticipantId": %d, "startOffsetMs": 22000, "endOffsetMs": 26000, "text": "창 밖"}
                  ]
                }
                """.formatted(INSTRUCTOR_PARTICIPANT_ID));

        // 침묵도 정상이다. 그 구간에 대해서는 목차로 답할 수 있다.
        assertThat(adapter.findIn(SESSION_ID, WINDOW_FROM_MS, WINDOW_TO_MS)).isEmpty();
    }

    private void insertTranscript(String transcriptDocument) {
        jdbcTemplate.update(
                "INSERT INTO transcripts (id, session_id, transcript_document, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                9_259_400L,
                SESSION_ID,
                transcriptDocument,
                NOW,
                NOW);
    }
}
