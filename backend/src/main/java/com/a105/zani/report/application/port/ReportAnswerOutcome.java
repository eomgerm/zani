package com.a105.zani.report.application.port;

import java.util.Optional;

/**
 * 질의응답 호출 결과. 성공이면 답변을, 실패면 사유를 담는다.
 *
 * <p>{@code Optional} 하나로 돌려주지 않는 이유는 호출부가 어떤 에러 코드를 낼지 정해야 하기 때문이다 — 형제 포트({@code ContentAnalysisOutcome})와 같은 모양이다.
 */
public record ReportAnswerOutcome(ReportAnswer answer, ReportAnswerFailure failure) {

    public static ReportAnswerOutcome success(ReportAnswer answer) {
        return new ReportAnswerOutcome(answer, null);
    }

    public static ReportAnswerOutcome failed(ReportAnswerFailure failure) {
        return new ReportAnswerOutcome(null, failure);
    }

    public Optional<ReportAnswer> value() {
        return Optional.ofNullable(answer);
    }

    public boolean failed() {
        return failure != null;
    }
}
