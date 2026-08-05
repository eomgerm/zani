package com.a105.zani.report.infrastructure.persistence.query;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.report.application.getinstructorreport.InstructorReportQueryPort;
import com.a105.zani.report.application.getinstructorreport.InstructorReportStats;
import com.a105.zani.report.application.getinstructorreport.InstructorReportView;
import com.a105.zani.report.infrastructure.persistence.entity.InstructorReportInsightJpaEntity;
import com.a105.zani.report.infrastructure.persistence.entity.InstructorReportJpaEntity;
import com.a105.zani.report.infrastructure.persistence.entity.InstructorReportScoreJpaEntity;
import com.a105.zani.report.infrastructure.persistence.entity.InstructorReportTipJpaEntity;
import com.a105.zani.report.infrastructure.persistence.repository.InstructorReportInsightJpaRepository;
import com.a105.zani.report.infrastructure.persistence.repository.InstructorReportJpaRepository;
import com.a105.zani.report.infrastructure.persistence.repository.InstructorReportScoreJpaRepository;
import com.a105.zani.report.infrastructure.persistence.repository.InstructorReportTipJpaRepository;

/**
 * 저장된 강사 리포트를 읽어 값 객체로 옮긴다.
 *
 * <p>엔티티가 밖으로 나가는 지점을 이 클래스 하나로 좁힌다. 자식 셋을 지연 로딩으로 따라가지 않고 리포트 ID 로 각각 한 번씩 조회하는 이유: 컬렉션 셋을 함께 조인하면 곱집합이 되고(스코어 4 ×
 * 인사이트 5 × 팁 4 = 80 행), 지연 로딩으로 두면 트랜잭션 밖에서 열릴 위험이 남는다.
 */
@Component
@RequiredArgsConstructor
public class InstructorReportQueryAdapter implements InstructorReportQueryPort {

    /** 강사는 세지 않는다. "총 수강생" 이므로 학생만이다. */
    private static final String COUNT_STUDENTS = """
            SELECT COUNT(*) FROM session_participants
             WHERE session_id = ? AND role = 'STUDENT'
            """;

    private static final String COUNT_ALERTS = """
            SELECT COUNT(*) FROM group_alerts WHERE session_id = ?
            """;

    private final InstructorReportJpaRepository reportRepository;
    private final InstructorReportScoreJpaRepository scoreRepository;
    private final InstructorReportInsightJpaRepository insightRepository;
    private final InstructorReportTipJpaRepository tipRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<InstructorReportView> findBySessionId(long sessionId) {
        return reportRepository.findBySessionId(sessionId).map(this::toRecord);
    }

    /**
     * 질문 수는 아직 채우지 못한다.
     *
     * <p>{@code student_reports.question_count} 는 112(학생 리포트)의 마이그레이션이 만드는 컬럼이고 아직 dev 에 없다. 같은 컬럼을 여기서 또 만들면 마이그레이션
     * 버전이 겹쳐 Flyway 가 기동을 거부한다 — 그 사고가 오늘 한 번 있었다.
     *
     * <p>필드를 응답에서 빼지 않고 {@code null} 로 두는 이유: 값이 없는 것과 필드가 없는 것은 화면이 다르게 다뤄야 하고, 계약이 흔들리면 FE 가 두 번 고쳐야 한다. 112 가 머지되면
     * {@code SELECT SUM(question_count) FROM student_reports WHERE session_id = ?} 한 줄로 채워진다.
     */
    @Override
    public InstructorReportStats stats(long sessionId, long durationSeconds) {
        Long students = jdbcTemplate.queryForObject(COUNT_STUDENTS, Long.class, sessionId);
        Long alerts = jdbcTemplate.queryForObject(COUNT_ALERTS, Long.class, sessionId);

        return new InstructorReportStats(
                students == null ? 0L : students, durationSeconds, null, alerts == null ? 0L : alerts);
    }

    private InstructorReportView toRecord(InstructorReportJpaEntity report) {
        return new InstructorReportView(
                report.getOverallFeedback(),
                report.getPublishedAt(),
                scores(report.getId()),
                insights(report.getId()),
                tips(report.getId()));
    }

    private List<InstructorReportView.ScoreRecord> scores(Long reportId) {
        return scoreRepository.findByInstructorReportIdOrderByIdAsc(reportId).stream()
                .map(InstructorReportQueryAdapter::toScore)
                .toList();
    }

    private List<InstructorReportView.InsightRecord> insights(Long reportId) {
        return insightRepository.findByInstructorReportIdOrderByStartedOffsetMsAscIdAsc(reportId).stream()
                .map(InstructorReportQueryAdapter::toInsight)
                .toList();
    }

    private List<InstructorReportView.TipRecord> tips(Long reportId) {
        return tipRepository.findByInstructorReportIdOrderByIdAsc(reportId).stream()
                .map(InstructorReportQueryAdapter::toTip)
                .toList();
    }

    /** 컬럼은 TINYINT(0~100) 이고 값 객체는 int 로 다룬다 — 바깥에서 Byte 산술을 하게 두지 않는다. */
    private static InstructorReportView.ScoreRecord toScore(InstructorReportScoreJpaEntity entity) {
        return new InstructorReportView.ScoreRecord(entity.getEvaluationType(), entity.getScore());
    }

    private static InstructorReportView.InsightRecord toInsight(InstructorReportInsightJpaEntity entity) {
        return new InstructorReportView.InsightRecord(
                entity.getInsightType(), entity.getContent(), entity.getStartedOffsetMs(), entity.getEndedOffsetMs());
    }

    private static InstructorReportView.TipRecord toTip(InstructorReportTipJpaEntity entity) {
        return new InstructorReportView.TipRecord(entity.getTipType(), entity.getTitle(), entity.getContent());
    }
}
