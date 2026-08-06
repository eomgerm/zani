package com.a105.zani.report.application.savestudentanalysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.report.domain.exception.InvalidStudentReportException;
import com.a105.zani.report.domain.model.RecommendationType;
import com.a105.zani.report.domain.model.ReviewRecommendation;
import com.a105.zani.report.domain.model.StudentReport;
import com.a105.zani.report.domain.repository.StudentReportRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudentAnalysisSaveService implements SaveStudentAnalysisUseCase {

    private final StudentReportRepository studentReportRepository;

    @Override
    @Transactional
    public Optional<Long> save(SaveStudentAnalysisCommand command) {
        StudentReport report = StudentReport.create(
                command.sessionId(),
                command.sessionParticipantId(),
                command.participationSummary(),
                command.questionCount(),
                usable(command.recommendations()));
        return studentReportRepository.saveIfAbsent(report);
    }

    /**
     * 추천으로 만들 수 있는 항목만 담는다.
     *
     * <p><b>한 줄이 불변식을 못 지켰다고 리포트를 통째로 버리지 않는다.</b> 예전에는 {@code map} 안에서 {@link ReviewRecommendation} 이 던지면 그대로 올라가 참여
     * 요약·질문 수·정상인 나머지 추천과 퀴즈까지 함께 사라졌다. 그 학생이 {@code FAILED} 로 집계되면 <b>세션 공개까지 막힌다</b> — 추천 한 줄이 수업 전체의 리포트를 못 나오게
     * 한다(S15P11A105-333).
     *
     * <p>호출부의 {@code ground()} 는 구간 번호·유형·구간 중복만 본다. 제목이나 설명이 비었는지는 보지 않으므로 그 값이 여기까지 온다.
     *
     * <p>추천 0개인 리포트는 유효하다 — 화면도 "복습 추천이 없어요" 를 그린다(REPORT-S-005).
     */
    private List<ReviewRecommendation> usable(List<SaveStudentAnalysisCommand.Recommendation> given) {
        List<ReviewRecommendation> usable = new ArrayList<>();
        for (SaveStudentAnalysisCommand.Recommendation recommendation :
                given == null ? List.<SaveStudentAnalysisCommand.Recommendation>of() : given) {
            try {
                usable.add(ReviewRecommendation.of(
                        RecommendationType.from(recommendation.type()),
                        recommendation.title(),
                        recommendation.description(),
                        recommendation.startedOffsetMs(),
                        recommendation.endedOffsetMs()));
            } catch (InvalidStudentReportException invalid) {
                log.warn(
                        "복습 추천 한 줄을 버립니다. title={}, 사유={}",
                        recommendation.title(),
                        invalid.errorCode().code());
            }
        }
        return usable;
    }
}
