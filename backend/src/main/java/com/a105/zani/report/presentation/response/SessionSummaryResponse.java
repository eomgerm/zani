package com.a105.zani.report.presentation.response;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.report.application.getsessionsummary.GetSessionSummaryResult;

@Schema(description = "종료된 수업의 공통 요약. 강사와 학생이 같은 값을 받는다")
public record SessionSummaryResponse(
        @Schema(
                description = "사후 공통 분석이 만든 수업 요약 한 문단",
                example = "이번 수업은 지역 상태에서 출발해 props drilling, Context 리렌더링, 메모이제이션 순으로 이어졌습니다.")
        String summary) {

    public static SessionSummaryResponse from(GetSessionSummaryResult result) {
        return new SessionSummaryResponse(result.summary());
    }
}
