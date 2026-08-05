package com.a105.zani.report.infrastructure.persistence.query;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.report.application.getinstructorreport.InstructorReportCounts;
import com.a105.zani.report.application.getinstructorreport.InstructorReportQueryPort;
import com.a105.zani.report.application.getinstructorreport.InstructorReportView;
import com.a105.zani.report.infrastructure.persistence.entity.InstructorReportInsightJpaEntity;
import com.a105.zani.report.infrastructure.persistence.entity.InstructorReportJpaEntity;
import com.a105.zani.report.infrastructure.persistence.entity.InstructorReportScoreJpaEntity;
import com.a105.zani.report.infrastructure.persistence.repository.InstructorReportInsightJpaRepository;
import com.a105.zani.report.infrastructure.persistence.repository.InstructorReportJpaRepository;
import com.a105.zani.report.infrastructure.persistence.repository.InstructorReportScoreJpaRepository;

/**
 * 저장된 강사 리포트를 읽어 값 객체로 옮긴다.
 *
 * <p>엔티티가 밖으로 나가는 지점을 이 클래스 하나로 좁힌다. 자식 둘을 지연 로딩으로 따라가지 않고 리포트 ID 로 각각 한 번씩 조회하는 이유: 컬렉션 둘을 함께 조인하면 곱집합이 되고(스코어 4 ×
 * 인사이트 5 = 20 행), 지연 로딩으로 두면 트랜잭션 밖에서 열릴 위험이 남는다.
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
    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<InstructorReportView> findBySessionId(long sessionId) {
        return reportRepository.findBySessionId(sessionId).map(this::toRecord);
    }

    @Override
    public InstructorReportCounts counts(long sessionId) {
        Long students = jdbcTemplate.queryForObject(COUNT_STUDENTS, Long.class, sessionId);
        Long alerts = jdbcTemplate.queryForObject(COUNT_ALERTS, Long.class, sessionId);

        return new InstructorReportCounts(students == null ? 0L : students, alerts == null ? 0L : alerts);
    }

    private InstructorReportView toRecord(InstructorReportJpaEntity report) {
        return new InstructorReportView(
                report.getOverallFeedback(),
                // 채팅 행 수를 세지 않는다. 무엇이 질문인지는 문장을 읽어야 알 수 있어 AI 가 판단해 굳혀 둔 값이다(V19).
                report.getQuestionCount(),
                report.getPublishedAt(),
                scores(report.getId()),
                insights(report.getId()));
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

    /** 컬럼은 TINYINT(0~100) 이고 값 객체는 int 로 다룬다 — 바깥에서 Byte 산술을 하게 두지 않는다. */
    private static InstructorReportView.ScoreRecord toScore(InstructorReportScoreJpaEntity entity) {
        return new InstructorReportView.ScoreRecord(entity.getEvaluationType(), entity.getScore());
    }

    private static InstructorReportView.InsightRecord toInsight(InstructorReportInsightJpaEntity entity) {
        return new InstructorReportView.InsightRecord(
                entity.getTitle(),
                entity.getContent(),
                entity.getSuggestion(),
                entity.getStartedOffsetMs(),
                entity.getEndedOffsetMs());
    }
}
