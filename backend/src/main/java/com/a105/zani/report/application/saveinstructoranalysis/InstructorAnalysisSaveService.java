package com.a105.zani.report.application.saveinstructoranalysis;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.report.domain.exception.InvalidInstructorReportException;
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
@Slf4j
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
        InstructorReport report = InstructorReport.create(
                command.sessionId(),
                command.overallFeedback(),
                command.questionCount(),
                scores,
                cards(command.insights()));
        return instructorReportRepository.saveIfAbsent(report);
    }

    /**
     * 카드로 만들 수 있는 항목만 담는다.
     *
     * <p><b>한 장이 불변식을 못 지켰다고 리포트를 통째로 버리지 않는다.</b> 예전에는 {@code map} 안에서 {@link ClassInsight#of} 가 던지면 그대로 올라가 종합
     * 피드백·분야별 평가·정상인 나머지 카드까지 함께 사라지고, 한 트랜잭션이라 부분 저장도 없었다. 실제로 제안이 빈 카드 한 장 때문에 강사 리포트가 0건이 됐다(S15P11A105-332).
     *
     * <p>호출부의 {@code ground()} 가 이미 "한 항목의 불일치로 리포트 전체를 버리지 않는다" 는 원칙으로 항목 단위로 거른다. 그 다음 단계인 이 변환만 전체를 터뜨리고 있었다 — 같은
     * 원칙을 여기까지 잇는다.
     *
     * <p>버린 항목은 로그로 남긴다. 조용히 사라지면 모델 응답이 규칙을 어기고 있다는 사실을 알 방법이 없다.
     */
    private List<ClassInsight> cards(List<SaveInstructorAnalysisCommand.Insight> given) {
        List<ClassInsight> cards = new ArrayList<>();
        for (SaveInstructorAnalysisCommand.Insight insight :
                given == null ? List.<SaveInstructorAnalysisCommand.Insight>of() : given) {
            try {
                cards.add(ClassInsight.of(
                        insight.title(),
                        insight.evidence(),
                        insight.suggestion(),
                        insight.startedOffsetMs(),
                        insight.endedOffsetMs()));
            } catch (InvalidInstructorReportException invalid) {
                log.warn(
                        "수업 인사이트 한 장을 버립니다. title={}, 사유={}",
                        insight.title(),
                        invalid.errorCode().code());
            }
        }
        return cards;
    }
}
