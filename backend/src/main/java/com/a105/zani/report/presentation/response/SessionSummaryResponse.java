package com.a105.zani.report.presentation.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.report.application.getsessionsummary.GetSessionSummaryResult;
import com.a105.zani.report.application.listsessionsections.SessionSectionView;

@Schema(description = "종료된 수업의 공통 요약. 강사와 학생이 같은 값을 받는다")
public record SessionSummaryResponse(
        @Schema(
                description = "사후 공통 분석이 만든 수업 요약 한 문단",
                example = "이번 수업은 지역 상태에서 출발해 props drilling, Context 리렌더링, 메모이제이션 순으로 이어졌습니다.")
        String summary,

        @Schema(description = "같은 분석이 나눈 내용 구간. 내용 타임라인이 아직 없는 세션은 빈 배열이며 오류가 아니다")
        List<Section> sections) {

    @Schema(description = "수업 내용 구간 하나")
    public record Section(
            @Schema(description = "구간 시작(ms)", example = "0")
            long startedOffsetMs,

            @Schema(description = "구간 종료(ms)", example = "519000")
            long endedOffsetMs,

            @Schema(description = "구간 제목", example = "상태 관리 개요")
            String title,

            @Schema(description = "구간 요약. 248 이 제목만 채운 세션은 null 이다", example = "지역 상태와 전역 상태를 가르는 기준을 예시로 설명했다.")
            String summary) {}

    public static SessionSummaryResponse from(GetSessionSummaryResult result) {
        return new SessionSummaryResponse(
                result.summary(),
                result.sections().stream()
                        .map(SessionSummaryResponse::toSection)
                        .toList());
    }

    /**
     * 강사 리포트({@code InstructorReportResponse.Section})와 달리 구간 요약까지 내린다.
     *
     * <p>그쪽은 타임라인 축에 이름을 붙이는 용도라 제목이면 충분하지만, 이 카드는 구간마다 무엇을 했는지 읽는 화면이다.
     */
    private static Section toSection(SessionSectionView section) {
        return new Section(section.startedOffsetMs(), section.endedOffsetMs(), section.title(), section.summary());
    }
}
