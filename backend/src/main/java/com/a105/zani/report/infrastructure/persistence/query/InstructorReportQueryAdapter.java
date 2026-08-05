package com.a105.zani.report.infrastructure.persistence.query;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.a105.zani.report.application.getinstructorreport.InstructorReportQueryPort;
import com.a105.zani.report.application.getinstructorreport.InstructorReportRecord;
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

    private final InstructorReportJpaRepository reportRepository;
    private final InstructorReportScoreJpaRepository scoreRepository;
    private final InstructorReportInsightJpaRepository insightRepository;
    private final InstructorReportTipJpaRepository tipRepository;

    @Override
    public Optional<InstructorReportRecord> findBySessionId(long sessionId) {
        return reportRepository.findBySessionId(sessionId).map(this::toRecord);
    }

    private InstructorReportRecord toRecord(InstructorReportJpaEntity report) {
        return new InstructorReportRecord(
                report.getOverallFeedback(),
                report.getPublishedAt(),
                scores(report.getId()),
                insights(report.getId()),
                tips(report.getId()));
    }

    private List<InstructorReportRecord.ScoreRecord> scores(Long reportId) {
        return scoreRepository.findByInstructorReportIdOrderByIdAsc(reportId).stream()
                .map(InstructorReportQueryAdapter::toScore)
                .toList();
    }

    private List<InstructorReportRecord.InsightRecord> insights(Long reportId) {
        return insightRepository.findByInstructorReportIdOrderByStartedOffsetMsAscIdAsc(reportId).stream()
                .map(InstructorReportQueryAdapter::toInsight)
                .toList();
    }

    private List<InstructorReportRecord.TipRecord> tips(Long reportId) {
        return tipRepository.findByInstructorReportIdOrderByIdAsc(reportId).stream()
                .map(InstructorReportQueryAdapter::toTip)
                .toList();
    }

    /** 컬럼은 TINYINT(0~100) 이고 값 객체는 int 로 다룬다 — 바깥에서 Byte 산술을 하게 두지 않는다. */
    private static InstructorReportRecord.ScoreRecord toScore(InstructorReportScoreJpaEntity entity) {
        return new InstructorReportRecord.ScoreRecord(entity.getEvaluationType(), entity.getScore());
    }

    private static InstructorReportRecord.InsightRecord toInsight(InstructorReportInsightJpaEntity entity) {
        return new InstructorReportRecord.InsightRecord(
                entity.getInsightType(), entity.getContent(), entity.getStartedOffsetMs(), entity.getEndedOffsetMs());
    }

    private static InstructorReportRecord.TipRecord toTip(InstructorReportTipJpaEntity entity) {
        return new InstructorReportRecord.TipRecord(entity.getTipType(), entity.getTitle(), entity.getContent());
    }
}
