package com.a105.zani.report.infrastructure.persistence.query;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SessionSummaryQueryAdapterTest {

    private static final long INSTRUCTOR_ID = 9_114_000L;
    private static final long SESSION_ID = 9_114_010L;
    private static final long OTHER_SESSION_ID = 9_114_011L;
    private static final String SUMMARY = "게시된 수업 요약";
    private static final LocalDateTime NOW =
            LocalDateTime.ofInstant(Instant.parse("2026-08-05T01:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private SessionSummaryQueryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, 'summary-query-9114000', 'summary-query@report.test', '강사', ?, ?)",
                INSTRUCTOR_ID,
                NOW,
                NOW);
        insertSession(SESSION_ID, "SQ114010");
        insertSession(OTHER_SESSION_ID, "SQ114011");
    }

    @Test
    @DisplayName("게시된 요약만 읽고, 다른 세션의 요약은 섞이지 않는다")
    void reads_only_the_published_summary_of_that_session() {
        insertSessionReport(9_114_030L, SESSION_ID, SUMMARY, NOW);
        insertSessionReport(9_114_031L, OTHER_SESSION_ID, "다른 세션 요약", NOW);

        assertThat(adapter.findPublishedSummaryBySessionId(SESSION_ID)).contains(SUMMARY);
    }

    @Test
    @DisplayName("게시 전 초안은 읽지 않는다")
    void hides_an_unpublished_summary() {
        insertSessionReport(9_114_030L, SESSION_ID, SUMMARY, null);

        assertThat(adapter.findPublishedSummaryBySessionId(SESSION_ID)).isEmpty();
    }

    @Test
    @DisplayName("요약 행이 없으면 비어 있다")
    void returns_empty_without_a_row() {
        assertThat(adapter.findPublishedSummaryBySessionId(SESSION_ID)).isEmpty();
    }

    private void insertSession(long id, String inviteCode) {
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at) VALUES (?, ?, '수업 요약 projection 테스트', ?,"
                        + " 'ENDED', 'COMPLETED', ?, ?, ?, ?)",
                id,
                INSTRUCTOR_ID,
                inviteCode,
                NOW.minusHours(1),
                NOW,
                NOW,
                NOW);
    }

    private void insertSessionReport(long id, long sessionId, String summary, LocalDateTime publishedAt) {
        jdbcTemplate.update(
                "INSERT INTO session_reports (id, session_id, summary, published_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                id,
                sessionId,
                summary,
                publishedAt,
                NOW,
                NOW);
    }
}
