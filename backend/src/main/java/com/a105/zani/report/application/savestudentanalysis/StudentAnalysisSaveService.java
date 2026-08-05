package com.a105.zani.report.application.savestudentanalysis;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.report.domain.model.RecommendationType;
import com.a105.zani.report.domain.model.ReviewRecommendation;
import com.a105.zani.report.domain.model.StudentReport;
import com.a105.zani.report.domain.repository.StudentReportRepository;

@Service
@RequiredArgsConstructor
public class StudentAnalysisSaveService implements SaveStudentAnalysisUseCase {

    private final StudentReportRepository studentReportRepository;

    @Override
    @Transactional
    public Optional<Long> save(SaveStudentAnalysisCommand command) {
        List<SaveStudentAnalysisCommand.Recommendation> given =
                command.recommendations() == null ? List.of() : command.recommendations();
        List<ReviewRecommendation> recommendations = given.stream()
                .map(recommendation -> ReviewRecommendation.of(
                        RecommendationType.from(recommendation.type()),
                        recommendation.title(),
                        recommendation.description(),
                        recommendation.startedOffsetMs(),
                        recommendation.endedOffsetMs()))
                .toList();
        StudentReport report = StudentReport.create(
                command.sessionId(),
                command.sessionParticipantId(),
                command.participationSummary(),
                command.questionCount(),
                recommendations);
        return studentReportRepository.saveIfAbsent(report);
    }
}
