package com.a105.zani.report.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.report.domain.exception.SessionAlreadyAnalyzedException;
import com.a105.zani.report.domain.model.SessionReport;
import com.a105.zani.report.domain.model.SessionSection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 공통 리포트 영속 어댑터의 DB 수준 계약 검증(S15P11A105-248).
 *
 * <p>fake 로는 확인할 수 없는 것 셋을 본다 — 요약과 구간이 <b>두 테이블에</b> 함께 들어가는지, 오프셋이 {@code started_offset_ms}·{@code ended_offset_ms}
 * 컬럼에 맞게 매핑되는지, 그리고 같은 세션을 두 번 저장하면 UK_SESSION_REPORTS_SESSION 이 잡아 구간이 두 배로 남지 않는지({@code session_sections} 에는 유니크 제약이
 * 없다).
 *
 * <p>로컬 MySQL 이 떠 있어야 통과하며, 테스트 트랜잭션은 종료 시 롤백되어 데이터를 남기지 않는다.
 */
@SpringBootTest
class SessionReportPersistenceAdapterTest {

    private static final long MEMBER_ID = 9_200_001L;
    private static final long SESSION_ID = 9_200_000_001L;
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private static final long CLASS_DURATION_MS = 45 * 60 * 1_000L;

    @Autowired
    private SessionReportPersistenceAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Hibernate 가 UTC 로 저장(jdbc.time_zone=UTC)하므로 직접 INSERT 할 때도 UTC 기준 시각을 넣는다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private void insertSession() {
        jdbcTemplate.update(
                "INSERT IGNORE INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                MEMBER_ID,
                "google-" + MEMBER_ID,
                MEMBER_ID + "@example.com",
                "공통 리포트 적재 테스트 강사",
                utc(NOW),
                utc(NOW));
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'ENDED', 'NOT_STARTED', ?, ?, ?, ?)",
                SESSION_ID,
                MEMBER_ID,
                "공통 리포트 적재 테스트",
                "RPT92001",
                utc(NOW.minusMillis(CLASS_DURATION_MS)),
                utc(NOW),
                utc(NOW.minusMillis(CLASS_DURATION_MS)),
                utc(NOW));
    }

    private static SessionReport report() {
        return SessionReport.create(
                SESSION_ID,
                "React 상태 관리를 다뤘다.",
                List.of(
                        SessionSection.of("상태 관리", "useState 와 useReducer 를 비교했다.", 0, 600_000),
                        SessionSection.of("Context 리렌더", "Provider value 의 참조를 설명했다.", 600_000, 1_800_000)),
                CLASS_DURATION_MS);
    }

    @Test
    @Transactional
    void storesTheSummaryAndTheSectionsInBothTables() {
        insertSession();

        adapter.save(report());

        String summary = jdbcTemplate.queryForObject(
                "SELECT summary FROM session_reports WHERE session_id = ?", String.class, SESSION_ID);
        assertEquals("React 상태 관리를 다뤘다.", summary);

        List<Map<String, Object>> sections = jdbcTemplate.queryForList(
                "SELECT title, summary, started_offset_ms, ended_offset_ms FROM session_sections"
                        + " WHERE session_id = ? ORDER BY started_offset_ms",
                SESSION_ID);
        assertEquals(2, sections.size());
        assertEquals("상태 관리", sections.getFirst().get("title"));
        assertEquals(0L, ((Number) sections.getFirst().get("started_offset_ms")).longValue());
        assertEquals(600_000L, ((Number) sections.getFirst().get("ended_offset_ms")).longValue());
        assertEquals(1_800_000L, ((Number) sections.getLast().get("ended_offset_ms")).longValue());
    }

    /**
     * 같은 세션을 두 번 저장하면 유니크 제약이 잡는다.
     *
     * <p>요약을 먼저 쓰는 순서가 여기서 값을 한다 — 구간을 먼저 썼다면 이 시점에 구간만 네 개로 남는다. 어댑터는 이 위반을 도메인 오류로 옮겨, 파이프라인이 500 이 아니라 "이미 분석됨"으로 읽게
     * 한다.
     */
    @Test
    @Transactional
    void refusesASecondReportForTheSameSessionWithoutDuplicatingSections() {
        insertSession();
        adapter.save(report());

        assertThrows(SessionAlreadyAnalyzedException.class, () -> adapter.save(report()));

        Long sections = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_sections WHERE session_id = ?", Long.class, SESSION_ID);
        assertEquals(2L, sections);
    }

    @Test
    @Transactional
    void reportsWhetherTheSessionHasAlreadyBeenAnalysed() {
        insertSession();
        assertFalse(adapter.existsBySessionId(SESSION_ID));

        adapter.save(report());

        assertTrue(adapter.existsBySessionId(SESSION_ID));
    }

    @Test
    @Transactional
    void stampsThePublishedTimeOnce() {
        insertSession();
        adapter.save(report());
        Instant publishedAt = Instant.parse("2026-08-05T03:00:00Z");

        assertTrue(adapter.markPublished(SESSION_ID, publishedAt));

        assertEquals(publishedAt, publishedAtOf(SESSION_ID));
    }

    /**
     * 두 번째 공개는 시각을 덮지 않는다.
     *
     * <p>공개 시각은 알림이 발송 대상을 찾는 조건이다(S15P11A105-116). 재시도가 그 값을 다시 쓰면 발견 순서가 흔들리고, 무엇보다 두 번째 호출이 첫 번째와 구분되지 않아 호출자가 "방금
     * 공개했다"고 잘못 판단한다.
     */
    @Test
    @Transactional
    void refusesToRestampAnAlreadyPublishedReport() {
        insertSession();
        adapter.save(report());
        Instant first = Instant.parse("2026-08-05T03:00:00Z");
        adapter.markPublished(SESSION_ID, first);

        assertFalse(adapter.markPublished(SESSION_ID, first.plusSeconds(600)));

        assertEquals(first, publishedAtOf(SESSION_ID));
    }

    @Test
    @Transactional
    void refusesToPublishAMissingReport() {
        insertSession();

        assertFalse(adapter.markPublished(SESSION_ID, Instant.parse("2026-08-05T03:00:00Z")));
    }

    /**
     * 저장된 공개 시각을 UTC 로 읽는다.
     *
     * <p>{@code published_at} 은 시간대 없는 {@code DATETIME(6)} 이고 값은 UTC 로 쓰인다. {@code Timestamp} 로 읽으면 JDBC 가 JVM 기본 시간대로
     * 해석해 KST 만큼 어긋난다.
     */
    private Instant publishedAtOf(long sessionId) {
        return jdbcTemplate
                .queryForObject(
                        "SELECT published_at FROM session_reports WHERE session_id = ?", LocalDateTime.class, sessionId)
                .toInstant(ZoneOffset.UTC);
    }
}
