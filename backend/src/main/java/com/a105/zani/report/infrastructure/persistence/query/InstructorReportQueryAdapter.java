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

    /**
     * 모델이 판단한 질문 수의 합.
     *
     * <p>{@code chat_messages} 를 세지 않는다. 공개 채팅에는 "감사합니다" 도 같은 모양으로 들어와 행 수를 세면 그것까지 질문이 되고, 물음표만 찾으면 "이 부분 다시 설명해주실 수
     * 있나요" 같은 완곡한 요청을 놓친다(V15 주석). 학생 리포트가 그 시점에 굳혀 둔 판정을 더한다.
     *
     * <p>{@code SUM} 은 대상 행이 없거나 모두 {@code NULL} 이면 {@code NULL} 을 준다. 0 으로 바꾸지 않는다 — "아무도 질문하지 않았다" 와 "아직 분석이 값을 내지
     * 못했다" 는 화면에서 다르게 보여야 한다.
     *
     * <p>공개 전 학생 리포트도 함께 센다. 강사가 보는 것은 개별 학생의 리포트가 아니라 수업 전체의 질문 수이고, 학생 각자의 공개 시점 때문에 그 합이 흔들릴 이유가 없다.
     */
    private static final String SUM_QUESTION_COUNT = """
            SELECT SUM(question_count) FROM student_reports WHERE session_id = ?
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

    @Override
    public InstructorReportStats stats(long sessionId, long durationSeconds) {
        Long students = jdbcTemplate.queryForObject(COUNT_STUDENTS, Long.class, sessionId);
        Integer questions = jdbcTemplate.queryForObject(SUM_QUESTION_COUNT, Integer.class, sessionId);
        Long alerts = jdbcTemplate.queryForObject(COUNT_ALERTS, Long.class, sessionId);

        return new InstructorReportStats(
                students == null ? 0L : students, durationSeconds, questions, alerts == null ? 0L : alerts);
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
