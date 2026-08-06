package com.a105.zani.report.infrastructure.gms;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.a105.zani.report.application.port.AnswerCitation;
import com.a105.zani.report.application.port.AnswerLine;
import com.a105.zani.report.application.port.ReportAnswer;
import com.a105.zani.report.application.port.ReportAnswerOutcome;
import com.a105.zani.report.application.port.ReportAnswerPort;
import com.a105.zani.report.application.port.ReportAnswerRequest;

/**
 * GMS 크레딧을 쓰지 않고 개발·테스트할 때 쓰는 질의응답 어댑터(S15P11A105-259).
 *
 * <p>{@code gms.mock-enabled} 로 실제 어댑터와 배타적으로 갈라진다. 조건이 겹치면 {@link ReportAnswerPort} 빈이 둘이라 기동이 실패한다. 운영에서 이 어댑터가 뜨는
 * 사고는 {@code GmsMockProfileGuard} 가 기동 시점에 막는다.
 *
 * <p>고정 문구를 내지 않고 <b>실제로 조회된 근거를 되읽는다.</b> 목업이 늘 같은 답을 내면 검색 레이어가 엉뚱한 구간을 집어 와도 화면이 똑같아 보여, 목업 환경에서 배선을 확인할 수 없다.
 *
 * <p>인용도 첫 발화에서 실제로 뽑는다. 시각을 지어내면 화면의 이동 버튼이 늘 같은 자리로 가서, 인용 → 영상 이동 배선이 끊겨도 눈치채지 못한다.
 */
@Component
@ConditionalOnProperty(prefix = "gms", name = "mock-enabled", havingValue = "true", matchIfMissing = true)
public class GmsReportAnswerMockAdapter implements ReportAnswerPort {

    private static final Logger log = LoggerFactory.getLogger(GmsReportAnswerMockAdapter.class);

    static final String NOT_ANCHORED_ANSWER = "예시 답변입니다. 시간 앵커가 없어 수업 전체 요약으로 답했습니다.";

    @Override
    public ReportAnswerOutcome answer(ReportAnswerRequest request) {
        if (!request.anchored()) {
            log.info(
                    "Mock report answer returned without an anchor for a {}-section outline",
                    request.outline().size());
            return ReportAnswerOutcome.success(new ReportAnswer(NOT_ANCHORED_ANSWER, List.of(), true));
        }

        String title = request.anchoredSection().title();
        List<AnswerCitation> citations = request.lines().isEmpty()
                ? List.of()
                : List.of(citationOf(request.lines().getFirst()));
        String answer = "예시 답변입니다. \"%s\" 구간의 발화 %d줄을 근거로 답했습니다."
                .formatted(title, request.lines().size());

        log.info(
                "Mock report answer returned for section '{}' with {} lines",
                title,
                request.lines().size());
        return ReportAnswerOutcome.success(new ReportAnswer(answer, citations, true));
    }

    private AnswerCitation citationOf(AnswerLine line) {
        return new AnswerCitation(line.startOffsetMs(), line.text());
    }
}
