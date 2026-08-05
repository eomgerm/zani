package com.a105.zani.report.application.savesessionanalysis;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.report.application.exception.InvalidSessionAnalysisException;
import com.a105.zani.report.application.exception.SessionAnalysisAlreadyStoredException;
import com.a105.zani.report.domain.exception.InvalidSessionReportException;
import com.a105.zani.report.domain.exception.SessionAlreadyAnalyzedException;
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

        List<SessionSection> sections;
        SessionReport report;
        try {
            sections = command.sections().stream()
                    .map(draft -> SessionSection.of(
                            draft.title(), draft.summary(), draft.startOffsetMs(), draft.endOffsetMs()))
                    .toList();
            report = SessionReport.create(
                    command.sessionId(), command.classSummary(), sections, command.classDurationMs());
        } catch (InvalidSessionReportException rejected) {
            // 도메인 예외를 애플리케이션 경계 타입으로 바꿔 내보낸다. 호출하는 도메인이 report 의 domain 계층을
            // 알면 그쪽 구조를 바꿀 때마다 같이 깨진다.
            throw new InvalidSessionAnalysisException(rejected);
        }

        try {
            sessionReportRepository.save(report);
        } catch (SessionAlreadyAnalyzedException raced) {
            // 존재 확인과 저장 사이에 다른 시도가 먼저 적재했다.
            //
            // 여기서 saved=false 로 정상 반환하지 않고 예외로 내보내는 이유: 이 메서드는 @Transactional 이고 이
            // 예외는 flush 가 유니크 제약에 걸린 뒤에 나온다. 정상 반환하면 스프링이 커밋을 시도하는데, flush 가
            // 실패한 영속성 컨텍스트는 쓸 수 없어 커밋이 다시 터진다. 경계 밖으로 내보내 롤백을 제대로 시키고,
            // "이미 있다"는 해석은 트랜잭션이 없는 호출부가 한다.
            throw new SessionAnalysisAlreadyStoredException(raced);
        }

        log.info("공통 분석 결과를 적재했습니다. sessionId={} sections={}", command.sessionId(), sections.size());
        return new SaveSessionAnalysisResult(command.sessionId(), true, sections.size());
    }
}
