package com.a105.zani.report.application.askreportquestion;

import java.util.List;

import com.a105.zani.report.application.port.AnswerCitation;
import com.a105.zani.report.application.port.ReportAnswer;

/**
 * 답변과, 검증을 통과한 인용.
 *
 * <p>포트 타입({@link ReportAnswer})을 그대로 내보내지 않는 이유는 표현 계층이 외부 시스템 계약에 묶이지 않게 하려는 것이다 — 지금은 모양이 같지만 GMS 응답 형태가 바뀌어도 API
 * 계약은 그대로 둘 수 있어야 한다.
 */
public record AskReportQuestionResult(String answer, List<AnswerCitation> citations, boolean grounded) {

    public static AskReportQuestionResult from(ReportAnswer answer) {
        return new AskReportQuestionResult(answer.answer(), answer.citations(), answer.grounded());
    }
}
