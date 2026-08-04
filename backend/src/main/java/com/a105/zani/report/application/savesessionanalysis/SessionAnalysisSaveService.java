package com.a105.zani.report.application.savesessionanalysis;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.report.domain.model.SessionReport;
import com.a105.zani.report.domain.model.SessionSection;
import com.a105.zani.report.domain.repository.SessionReportRepository;

/**
 * 공통 분석 결과를 세션당 한 번만 적재한다(S15P11A105-248).
 *
 * <p>이미 리포트가 있으면 아무것도 하지 않는다. 재시도는 단계를 되돌리지 않고 같은 단계를 다시 실행하므로(S15P11A105-107), 성공한 뒤 도착한 재시도가 요약을 덮거나 구간을 두 배로 늘리면 안
 * 된다. {@code session_sections} 에는 유니크 제약이 없어 중복이 조용히 쌓인다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionAnalysisSaveService implements SaveSessionAnalysisUseCase {

    private final SessionReportRepository sessionReportRepository;

    @Override
    @Transactional
    public SaveSessionAnalysisResult save(SaveSessionAnalysisCommand command) {
        if (sessionReportRepository.existsBySessionId(command.sessionId())) {
            log.info("공통 분석 결과가 이미 있어 적재를 건너뜁니다. sessionId={}", command.sessionId());
            return new SaveSessionAnalysisResult(command.sessionId(), false, 0);
        }

        List<SessionSection> sections = command.sections().stream()
                .map(draft ->
                        SessionSection.of(draft.title(), draft.summary(), draft.startOffsetMs(), draft.endOffsetMs()))
                .toList();
        SessionReport report =
                SessionReport.create(command.sessionId(), command.classSummary(), sections, command.classDurationMs());
        sessionReportRepository.save(report);

        log.info("공통 분석 결과를 적재했습니다. sessionId={} sections={}", command.sessionId(), sections.size());
        return new SaveSessionAnalysisResult(command.sessionId(), true, sections.size());
    }
}
