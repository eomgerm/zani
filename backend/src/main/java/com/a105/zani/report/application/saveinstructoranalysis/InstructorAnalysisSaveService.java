package com.a105.zani.report.application.saveinstructoranalysis;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.report.domain.model.ClassInsight;
import com.a105.zani.report.domain.model.EvaluationType;
import com.a105.zani.report.domain.model.InstructorReport;
import com.a105.zani.report.domain.repository.InstructorReportRepository;

/**
 * 강사 분석 결과를 리포트·점수·인사이트로 한 트랜잭션에 넣는다.
 *
 * <p>LLM 호출은 이 트랜잭션 밖에 있어야 한다 — 수 초 걸리는 호출을 안에 넣으면 커넥션을 그동안 붙잡는다. 호출부({@code AnalyzeSessionInstructorService})가 트랜잭션이
 * 아니어서 그 경계가 지켜진다.
 */
@Service
@RequiredArgsConstructor
public class InstructorAnalysisSaveService implements SaveInstructorAnalysisUseCase {

    private final InstructorReportRepository instructorReportRepository;

    @Override
    @Transactional
    public Optional<Long> save(SaveInstructorAnalysisCommand command) {
        Map<EvaluationType, Integer> scores = new EnumMap<>(EvaluationType.class);
        for (SaveInstructorAnalysisCommand.Score given :
                command.scores() == null ? List.<SaveInstructorAnalysisCommand.Score>of() : command.scores()) {
            scores.put(EvaluationType.from(given.evaluationType()), given.score());
        }
        List<ClassInsight> insights = (command.insights() == null
                        ? List.<SaveInstructorAnalysisCommand.Insight>of()
                        : command.insights())
                .stream()
                        .map(insight -> ClassInsight.of(
                                insight.title(),
                                insight.evidence(),
                                insight.suggestion(),
                                insight.startedOffsetMs(),
                                insight.endedOffsetMs()))
                        .toList();
        InstructorReport report = InstructorReport.create(
                command.sessionId(), command.overallFeedback(), command.questionCount(), scores, insights);
        return instructorReportRepository.saveIfAbsent(report);
    }
}
