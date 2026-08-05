package com.a105.zani.report.presentation.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.report.application.getinstructorreport.GetInstructorReportResult;
import com.a105.zani.report.application.getinstructorreport.InstructorReportView;
import com.a105.zani.report.application.listsessionsections.SessionSectionView;

/**
 * 강사 리포트 응답.
 *
 * <p><b>학생 식별자와 학생별 값을 어떤 필드로도 담지 않는다</b>(REPORT-I-002). 필드를 늘릴 때 이 문장을 먼저 읽어라 — 강사 화면에서 개인을 짚어낼 수 있게 되면 참여도 측정이 감시로
 * 바뀐다.
 *
 * <p><b>집중 흐름은 여기 없다.</b> {@code GET /reports/attention/group} 이 준다. 같은 값을 두 경로로 내리면 계산 규칙이 바뀔 때 한쪽만 고쳐질 자리가 생긴다.
 */
@Schema(description = "종료된 수업의 강사 리포트. AI 가 만든 종합 피드백·분야별 평가·수업 인사이트로 이루어진다. 인사이트 한 장이 제목·근거·제안·구간을 함께 갖는다.")
public record InstructorReportResponse(
        @Schema(description = "AI 가 생성한 수업 종합 피드백", example = "이번 수업은 전반적으로 논리적인 흐름과 단계적인 설명이…")
        String overallFeedback,

        @Schema(description = "한눈에 보기 집계. 집중 구간 비율은 여기 없다 — 집중 흐름 응답에서 화면이 계산한다.")
        Stats stats,

        @Schema(description = "분야별 평가. 순서는 저장 순서이며 화면이 배치를 정한다.")
        List<Score> scores,

        @Schema(description = "수업 인사이트. 구간이 이른 것부터이고, 수업 전체를 가리키는 항목이 앞에 온다.")
        List<Insight> insights,

        @Schema(description = "수업 내용 구간. 내용 타임라인이 아직 없는 세션은 빈 배열이며 오류가 아니다.")
        List<Section> sections) {

    @Schema(description = "한눈에 보기 집계")
    public record Stats(
            @Schema(description = "이 수업에 들어온 적 있는 학생 수. 강사는 세지 않는다.", example = "32")
            long studentCount,

            @Schema(description = "수업 길이(초). 종료 시각을 저장하기 전에 끝난 과거 세션은 0.", example = "7500")
            long durationSeconds,

            @Schema(
                    description =
                            "모델이 판단한 질문 수의 합. 채팅 행 수가 아니다 — \"감사합니다\" 같은 발화까지 세지 않으려면 문장을 읽어야 한다. 분석이 값을 내지 못했으면 null 이며 0 이 아니다.",
                    example = "184")
            Integer questionCount,

            @Schema(description = "수업 중 발생한 이해도 알림 횟수", example = "7")
            long alertCount) {}

    @Schema(description = "분야별 평가 한 항목")
    public record Score(
            @Schema(
                    description = "평가 분야",
                    allowableValues = {"DELIVERY", "STRUCTURE_FLOW", "INTERACTION", "DIFFICULTY_CONTROL"},
                    example = "DELIVERY")
            String evaluationType,

            @Schema(description = "0~100. 퍼센트가 아니라 점수다.", example = "88")
            int score) {}

    @Schema(description = "수업 인사이트 한 항목. 제목·근거·제안·구간이 한 장을 이룬다.")
    public record Insight(
            @Schema(description = "AI 가 직접 지은 제목. 유형 목록이 없다.", example = "어려운 구간 보강")
            String title,

            @Schema(description = "그렇게 판단한 근거(화면의 \"관찰\" 자리)")
            String content,

            @Schema(description = "AI 가 제시한 개선 제안(화면의 \"TIP\" 자리). 없을 수 있다.", example = "추가 예시 코드와 실습 시간을 늘려보세요.")
            String suggestion,

            @Schema(description = "대상 구간 시작(ms). 수업 전체를 가리키면 null.", example = "4800000")
            Long startedOffsetMs,

            @Schema(description = "대상 구간 종료(ms). 수업 전체를 가리키면 null.", example = "6000000")
            Long endedOffsetMs) {}

    @Schema(description = "수업 내용 구간 하나")
    public record Section(
            @Schema(description = "구간 시작(ms)", example = "0")
            long startedOffsetMs,

            @Schema(description = "구간 종료(ms)", example = "519000")
            long endedOffsetMs,

            @Schema(description = "구간 제목", example = "상태 관리 개요")
            String title) {}

    public static InstructorReportResponse from(GetInstructorReportResult result) {
        return new InstructorReportResponse(
                result.overallFeedback(),
                new Stats(
                        result.stats().studentCount(),
                        result.stats().durationSeconds(),
                        result.stats().questionCount(),
                        result.stats().alertCount()),
                result.scores().stream().map(InstructorReportResponse::toScore).toList(),
                result.insights().stream()
                        .map(InstructorReportResponse::toInsight)
                        .toList(),
                result.sections().stream()
                        .map(InstructorReportResponse::toSection)
                        .toList());
    }

    private static Score toScore(InstructorReportView.ScoreRecord score) {
        return new Score(score.evaluationType(), score.score());
    }

    private static Insight toInsight(InstructorReportView.InsightRecord insight) {
        return new Insight(
                insight.title(),
                insight.content(),
                insight.suggestion(),
                insight.startedOffsetMs(),
                insight.endedOffsetMs());
    }

    private static Section toSection(SessionSectionView section) {
        return new Section(section.startedOffsetMs(), section.endedOffsetMs(), section.title());
    }
}
