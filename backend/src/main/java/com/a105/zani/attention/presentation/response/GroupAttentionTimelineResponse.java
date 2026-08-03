package com.a105.zani.attention.presentation.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.attention.application.getattentiontimeline.GetGroupAttentionTimelineResult;
import com.a105.zani.attention.domain.model.timeline.DistractionInterval;
import com.a105.zani.attention.domain.model.timeline.GroupSignalPoint;

/**
 * 강사용 익명 집단 타임라인 응답.
 *
 * <p>학생 식별자와 학생별 값을 어떤 필드로도 담지 않는다(REPORT-I-002 · ALERT-004). 필드를 늘릴 때 이 문장을 먼저 읽어라 — 강사 화면에서 개인을 짚어낼 수 있게 되면 참여도 측정
 * 자체가 감시로 바뀐다.
 */
@Schema(description = "종료된 수업의 익명 집단 참여도 타임라인. 학생 식별자와 학생별 값은 담기지 않는다.")
public record GroupAttentionTimelineResponse(
        @Schema(description = "점 사이 간격(초)", example = "5") int intervalSeconds,

        @Schema(description = "타임라인이 덮는 길이(초). 수업 길이가 아니라 관측이 있는 마지막 시각까지다. 관측이 없으면 0.", example = "5400")
        long durationSeconds,

        @Schema(description = "5초 격자 점 목록. 관측이 한 건도 없으면 빈 배열이다.")
        List<Point> points,

        @Schema(description = "확인 필요 비율이 높게 이어진 구간") List<Interval> distractedIntervals) {

    /**
     * 격자 한 점.
     *
     * <p>모든 {@code *Ratio} 는 <b>0.0~1.0 분수</b>다. 표시할 때만 100 을 곱한다. {@code null} 은 값 없음이며 절대 0 이 아니다.
     */
    // 학생 응답의 중첩 레코드도 Point 라, 이름을 지정하지 않으면 springdoc 이 두 스키마를 하나로 합친다.
    // 합쳐지면 생성된 문서가 강사 응답도 focusPercent 를 갖는 것처럼 말하게 된다.
    @Schema(name = "GroupAttentionTimelinePoint", description = "격자 한 점. 모든 비율은 0.0~1.0 분수이고 null 은 값 없음이다.")
    public record Point(
            @Schema(description = "세션 시작 기준 경과 초", example = "300")
            long offsetSeconds,

            @Schema(description = "접속 1분을 넘긴 학생 수(제외 전)", example = "30")
            int connectedCount,

            @Schema(description = "위에서 측정 불가 1분 지속자를 뺀 수", example = "28")
            int eligibleCount,

            @Schema(
                    description = "확인 필요 비율. 분모는 eligibleCount 다. eligibleCount 가 5 미만이면 null.",
                    example = "0.32",
                    nullable = true)
            Double checkNeededRatio,

            @Schema(
                    description = "카메라 OFF 비율. 분모는 connectedCount 로 checkNeededRatio 와 **다르다** — 두 값을 더하거나"
                            + " 비교하면 안 된다. connectedCount 가 5 미만이면 null.",
                    example = "0.07",
                    nullable = true)
            Double cameraOffRatio,

            @Schema(description = "헷갈려요 비율. 분모는 eligibleCount.", example = "0.10", nullable = true)
            Double confusedRatio,

            @Schema(description = "놓쳤어요 비율. 분모는 eligibleCount.", example = "0.10", nullable = true)
            Double missedRatio,

            @Schema(description = "무응답 비율. 분모는 eligibleCount.", example = "0.07", nullable = true)
            Double nonResponseRatio,

            @Schema(description = "판단 불가 비율. 분모는 eligibleCount.", example = "0.05", nullable = true)
            Double unmeasurableRatio) {

        private static Point from(GroupSignalPoint point) {
            return new Point(
                    point.offsetSeconds(),
                    point.connectedCount(),
                    point.eligibleCount(),
                    point.checkNeededRatio(),
                    point.cameraOffRatio(),
                    point.confusedRatio(),
                    point.missedRatio(),
                    point.nonResponseRatio(),
                    point.unmeasurableRatio());
        }
    }

    @Schema(name = "DistractedInterval", description = "흐트러짐 구간")
    public record Interval(
            @Schema(description = "시작 초", example = "300") long startSeconds,
            @Schema(description = "종료 초", example = "380") long endSeconds) {

        private static Interval from(DistractionInterval interval) {
            return new Interval(interval.startSeconds(), interval.endSeconds());
        }
    }

    public static GroupAttentionTimelineResponse from(GetGroupAttentionTimelineResult result) {
        return new GroupAttentionTimelineResponse(
                result.intervalSeconds(),
                result.durationSeconds(),
                result.points().stream().map(Point::from).toList(),
                result.distractedIntervals().stream().map(Interval::from).toList());
    }
}
