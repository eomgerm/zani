package com.a105.zani.report.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.report.domain.model.ClassInsight;
import com.a105.zani.report.domain.model.EvaluationType;
import com.a105.zani.report.domain.model.InstructorReport;
import com.a105.zani.report.domain.repository.InstructorReportRepository;

import static org.assertj.core.api.Assertions.assertThat;

/** 멱등을 UK_INSTRUCTOR_REPORTS_SESSION 에 맡긴다. 실제 스키마가 필요해 로컬 MySQL 이 떠 있어야 통과한다. */
@SpringBootTest
@Transactional
class InstructorReportPersistenceAdapterTest {

    private static final Instant PUBLISHED_AT = Instant.parse("2026-08-05T03:00:00Z");

    @Autowired
    private InstructorReportRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long sessionId;

    @BeforeEach
    void setUp() {
        sessionId = insertSession(insertMember());
    }

    private static Map<EvaluationType, Integer> scores() {
        Map<EvaluationType, Integer> scores = new EnumMap<>(EvaluationType.class);
        scores.put(EvaluationType.DELIVERY, 88);
        scores.put(EvaluationType.STRUCTURE_FLOW, 84);
        scores.put(EvaluationType.INTERACTION, 71);
        scores.put(EvaluationType.DIFFICULTY_CONTROL, 76);
        return scores;
    }

    private InstructorReport report(String feedback) {
        return InstructorReport.create(
                sessionId,
                feedback,
                12,
                scores(),
                List.of(
                        ClassInsight.of("어려운 구간 보강", "확인 필요 신호와 질문이 겹쳤습니다.", "실습을 늘려보세요.", 520_000L, 921_000L),
                        ClassInsight.of("후반 흐름 회복", "후반부 참여도가 회복됐습니다.", "같은 순서를 유지해보세요.", null, null)));
    }

    @Test
    @DisplayName("리포트와 점수 4행·인사이트를 함께 넣고 리포트 ID 를 준다")
    void inserts_the_report_with_its_scores_and_insights() {
        Optional<Long> reportId = repository.saveIfAbsent(report("종합 피드백입니다."));

        assertThat(reportId).isPresent();
        assertThat(questionCountOf(reportId.get())).isEqualTo(12);
        assertThat(scoreRowsOf(reportId.get())).isEqualTo(4);
        assertThat(insightTitlesOf(reportId.get())).containsExactly("어려운 구간 보강", "후반 흐름 회복");
        assertThat(insightStartOffsetsOf(reportId.get())).containsExactly(520_000L, null);
    }

    @Test
    @DisplayName("이미 리포트가 있으면 빈 값을 주고 점수·인사이트를 덧붙이지 않는다")
    void does_not_append_to_someone_elses_report() {
        Long first = repository.saveIfAbsent(report("첫 번째")).orElseThrow();

        assertThat(repository.saveIfAbsent(report("두 번째"))).isEmpty();
        assertThat(scoreRowsOf(first)).isEqualTo(4);
        assertThat(insightTitlesOf(first)).hasSize(2);
        assertThat(reportRowsOfSession()).isEqualTo(1);
    }

    @Test
    @DisplayName("인사이트가 0개인 리포트도 저장한다")
    void stores_a_report_without_insights() {
        InstructorReport empty = InstructorReport.create(sessionId, "종합 피드백입니다.", 0, scores(), List.of());

        Long reportId = repository.saveIfAbsent(empty).orElseThrow();

        assertThat(insightTitlesOf(reportId)).isEmpty();
        assertThat(scoreRowsOf(reportId)).isEqualTo(4);
    }

    /**
     * 강사 리포트 조회가 이 시각으로 열람 가능 여부를 정한다. 찍히지 않으면 점수·인사이트가 다 있어도 화면은 "아직 리포트가 만들어지지 않았어요" 를 그린다(S15P11A105-312).
     *
     * <p>읽을 때 {@code LocalDateTime} 을 쓰는 이유: {@code published_at} 은 시간대 없는 {@code DATETIME(6)} 이고 값은 UTC 다.
     * {@code Instant} 로 읽으면 JVM 기본 시간대(KST)로 해석되어 9시간 어긋난 값이 통과한다.
     */
    @Test
    @DisplayName("공개 시각을 UTC 로 찍는다")
    void stamps_the_published_time_in_utc() {
        repository.saveIfAbsent(report("종합 피드백입니다."));

        assertThat(repository.markPublished(sessionId, PUBLISHED_AT)).isTrue();

        assertThat(publishedAtOfSession()).isEqualTo(PUBLISHED_AT);
    }

    /** 재시도가 시각을 덮으면 안 된다. 공개는 되돌릴 수 없는 일이라 두 번째 호출이 성공한 것처럼 보이면 안 된다. */
    @Test
    @DisplayName("이미 공개된 리포트는 시각을 덮지 않는다")
    void refuses_to_restamp_an_already_published_report() {
        repository.saveIfAbsent(report("종합 피드백입니다."));
        repository.markPublished(sessionId, PUBLISHED_AT);

        assertThat(repository.markPublished(sessionId, PUBLISHED_AT.plusSeconds(600)))
                .isFalse();

        assertThat(publishedAtOfSession()).isEqualTo(PUBLISHED_AT);
    }

    @Test
    @DisplayName("리포트가 없으면 공개하지 않는다")
    void refuses_to_publish_a_missing_report() {
        assertThat(repository.markPublished(sessionId, PUBLISHED_AT)).isFalse();
    }

    @Test
    @DisplayName("published_at 은 채우지 않는다 — 공개는 파이프라인이 일괄로 한다")
    void leaves_published_at_null() {
        Long reportId = repository.saveIfAbsent(report("종합 피드백입니다.")).orElseThrow();

        assertThat(jdbcTemplate.queryForList(
                        "SELECT published_at FROM instructor_reports WHERE id = ?", java.sql.Timestamp.class, reportId))
                .containsExactly((java.sql.Timestamp) null);
    }

    /** 공개 전 리포트가 갖춰졌는지 보는 조회다. 없는데 참을 주면 빈 강사 리포트가 공개되고 메일이 나간다. */
    @Test
    @DisplayName("세션별 리포트 존재를 답한다")
    void reports_whether_the_session_has_an_instructor_report() {
        assertThat(repository.existsBySessionId(sessionId)).isFalse();

        repository.saveIfAbsent(report("종합 피드백입니다."));

        assertThat(repository.existsBySessionId(sessionId)).isTrue();
    }

    private long insertMember() {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, '강사 리포트 저장 테스트', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                id,
                "google-" + id,
                id + "@example.com");
        return id;
    }

    private long insertSession(long hostMemberId) {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at)"
                        + " VALUES (?, ?, '상태 관리 수업', ?, 'ENDED', 'PROCESSING', UTC_TIMESTAMP(6),"
                        + " UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                id,
                hostMemberId,
                inviteCode(id));
        return id;
    }

    /** invite_code 는 CHAR(8) 이고 유니크다. TSID 뒷자리를 36진수로 접어 충돌을 피한다. */
    private static String inviteCode(long id) {
        String encoded = Long.toString(Math.abs(id), 36).toUpperCase();
        return encoded.length() <= 8 ? encoded : encoded.substring(encoded.length() - 8);
    }

    private Instant publishedAtOfSession() {
        return jdbcTemplate
                .queryForObject(
                        "SELECT published_at FROM instructor_reports WHERE session_id = ?",
                        LocalDateTime.class,
                        sessionId)
                .toInstant(ZoneOffset.UTC);
    }

    private int questionCountOf(long reportId) {
        return jdbcTemplate.queryForObject(
                "SELECT question_count FROM instructor_reports WHERE id = ?", Integer.class, reportId);
    }

    private int reportRowsOfSession() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM instructor_reports WHERE session_id = ?", Integer.class, sessionId);
    }

    private int scoreRowsOf(long reportId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM instructor_report_scores WHERE instructor_report_id = ?",
                Integer.class,
                reportId);
    }

    private List<String> insightTitlesOf(long reportId) {
        return jdbcTemplate.queryForList(
                "SELECT title FROM instructor_report_insights WHERE instructor_report_id = ? ORDER BY id ASC",
                String.class,
                reportId);
    }

    private List<Long> insightStartOffsetsOf(long reportId) {
        return jdbcTemplate.queryForList(
                "SELECT started_offset_ms FROM instructor_report_insights"
                        + " WHERE instructor_report_id = ? ORDER BY id ASC",
                Long.class,
                reportId);
    }
}
