package com.a105.zani.attention.presentation.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.attention.application.getattentiontimeline.GetMyAttentionTimelineResult;
import com.a105.zani.attention.domain.model.timeline.FocusTimelinePoint;
import com.a105.zani.attention.domain.model.timeline.StudentTimelineState;

/**
 * 학생 본인의 집중 흐름 응답.
 *
 * <p>평균 점수·타인 비교·모델 확률·검출기 단계를 담지 않는다(REPORT-S-010). 자기 점수를 남과 견주게 만드는 순간 이 지표는 학습을 돕는 것이 아니라 등수가 된다.
 */
@Schema(description = "종료된 수업에서 본인의 집중 흐름. 평균·타인 비교·모델 내부 값은 담기지 않는다.")
public record MyAttentionTimelineResponse(
        @Schema(description = "점 사이 간격(초)", example = "5") int intervalSeconds,

        @Schema(description = "타임라인이 덮는 길이(초). 수업 길이가 아니라 본인 관측이 있는 마지막 시각까지다. 관측이 없으면 0.", example = "5400")
        long durationSeconds,

        @Schema(description = "5초 격자 점 목록. 관측이 한 건도 없으면 빈 배열이다.")
        List<Point> points) {

    /**
     * 격자 한 점.
     *
     * <p>{@code focusPercent} 는 <b>0~100 정수</b>다. 강사 응답의 0.0~1.0 분수와 단위가 다르다. {@code null} 은 값 없음이며 0 점이 아니다 — 카메라를 끈
     * 시간도, 판단하지 못한 시간도 0 점이 아니다.
     */
    // 강사 응답의 중첩 레코드도 Point 라 이름을 갈라 둔다. 이유는 GroupAttentionTimelineResponse 에 적었다.
    @Schema(name = "MyAttentionTimelinePoint", description = "격자 한 점. focusPercent 는 0~100 정수이고 null 은 값 없음이다.")
    public record Point(
            @Schema(description = "세션 시작 기준 경과 초", example = "300")
            long offsetSeconds,

            @Schema(
                    description = "집중 점수(0~100 정수). 측정 가능 시간이 창의 70% 를 못 채우면 null 이며 0 이 아니다.",
                    example = "82",
                    nullable = true)
            Integer focusPercent,

            @Schema(
                    description = "그 시각의 상태. CHECK_NEEDED 는 헷갈려요·놓쳤어요·무응답을 묶은 값이다. 관측이 없거나 상태가" + " 정해지지 않았으면 null.",
                    example = "GOOD",
                    nullable = true)
            StudentTimelineState state) {

        private static Point from(FocusTimelinePoint point) {
            return new Point(point.offsetSeconds(), point.focusPercent(), point.state());
        }
    }

    public static MyAttentionTimelineResponse from(GetMyAttentionTimelineResult result) {
        return new MyAttentionTimelineResponse(
                result.intervalSeconds(),
                result.durationSeconds(),
                result.points().stream().map(Point::from).toList());
    }
}
