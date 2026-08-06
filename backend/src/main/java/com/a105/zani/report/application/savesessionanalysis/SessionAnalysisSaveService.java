package com.a105.zani.report.application.savesessionanalysis;

import java.util.ArrayList;
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

    /**
     * 구간으로 만들 수 있는 초안만 담는다.
     *
     * <p><b>하나가 불변식을 못 지켰다고 공통 분석을 통째로 버리지 않는다.</b> 예전에는 {@code map} 안에서 {@link SessionSection} 이 던지면 그대로 올라가고, 그 예외는
     * 재시도 대상도 아니어서 세션이 <b>즉시 영구 실패</b>했다 — 요약이 빈 구간 하나로 그 수업의 리포트·퀴즈·타임라인이 전부 없어진다(S15P11A105-333).
     *
     * <p>남은 구간이 0개면 그때는 실패한다. 구간은 학생·강사 분석과 타임라인의 <b>입력</b>이라 하나도 없으면 뒤 단계가 근거를 붙일 자리가 없다 — 그 판정은
     * {@code SessionReport.create} 가 이미 갖고 있어 여기서 따로 세지 않는다.
     *
     * <p>가운데 구간이 빠져 생기는 공백은 문제가 되지 않는다. {@code SessionReport} 가 막는 것은 겹침이고 공백은 허용한다.
     */
    private List<SessionSection> usable(List<SessionSectionDraft> drafts) {
        List<SessionSection> usable = new ArrayList<>();
        for (SessionSectionDraft draft : drafts == null ? List.<SessionSectionDraft>of() : drafts) {
            try {
                usable.add(
                        SessionSection.of(draft.title(), draft.summary(), draft.startOffsetMs(), draft.endOffsetMs()));
            } catch (InvalidSessionReportException rejected) {
                log.warn(
                        "내용 구간 하나를 버립니다. title={}, 시작={}ms, 사유={}",
                        draft.title(),
                        draft.startOffsetMs(),
                        rejected.errorCode().code());
            }
        }
        return usable;
    }

    @Override
    @Transactional
    public SaveSessionAnalysisResult save(SaveSessionAnalysisCommand command) {
        if (sessionReportRepository.existsBySessionId(command.sessionId())) {
            log.info("공통 분석 결과가 이미 있어 적재를 건너뜁니다. sessionId={}", command.sessionId());
            return new SaveSessionAnalysisResult(command.sessionId(), false, 0);
        }

        SessionReport report;
        try {
            report = SessionReport.create(
                    command.sessionId(), command.classSummary(), usable(command.sections()), command.classDurationMs());
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

        // 초안 수가 아니라 실제로 적재된 구간 수를 센다. 버린 초안이 있으면 두 값이 다르다.
        int stored = report.sections().size();
        log.info("공통 분석 결과를 적재했습니다. sessionId={} sections={}", command.sessionId(), stored);
        return new SaveSessionAnalysisResult(command.sessionId(), true, stored);
    }
}
