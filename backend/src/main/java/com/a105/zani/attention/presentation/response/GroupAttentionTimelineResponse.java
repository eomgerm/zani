package com.a105.zani.attention.presentation.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.attention.application.getattentiontimeline.GetGroupAttentionTimelineResult;
import com.a105.zani.attention.domain.model.timeline.DistractionInterval;
import com.a105.zani.attention.domain.model.timeline.GroupFocusBucket;
import com.a105.zani.attention.domain.model.timeline.GroupSignalPoint;
import com.a105.zani.attention.domain.model.timeline.SectionFocusAverage;

/**
 * 강사용 익명 집단 타임라인 응답.
 *
 * <p>학생 식별자와 학생별 값을 어떤 필드로도 담지 않는다(REPORT-I-002 · ALERT-004). 필드를 늘릴 때 이 문장을 먼저 읽어라 — 강사 화면에서 개인을 짚어낼 수 있게 되면 참여도 측정
 * 자체가 감시로 바뀐다.
 *
 * <p>격자가 둘이라 배열이 나뉜다. 한 배열에 섞으면 30초 값이 5초 점 6개 중 5개에서 {@code null} 이 되고, FE 는 그 {@code null} 을 회색 공백으로 그리므로 선이 아예 안
 * 그려진다(설계 문서 §3.1).
 */
@Schema(description = "종료된 수업의 익명 집단 참여도 타임라인. 격자가 둘이라 배열이 나뉜다. 학생 식별자와 학생별 값은 담기지 않는다.")
public record GroupAttentionTimelineResponse(
        @Schema(description = "타임라인이 덮는 길이(초). 수업 길이가 아니라 관측이 있는 마지막 시각까지다. 관측이 없으면 0.", example = "5400")
        long durationSeconds,

        @Schema(description = "집중 흐름. 겹치지 않는 30초 구간의 4단계 평균이며 주 계열이다.")
        FocusFlow focusFlow,

        @Schema(description = "신호 비율. 5초 격자이며 보조 계열이다. focusFlow 와 척도가 달라 같은 축에 놓지 않는다.")
        Signals signals,

        @Schema(description = "확인 필요 비율이 높게 이어진 구간") List<Interval> distractedIntervals,

        @Schema(description = "수업 내용 구간별 집중 흐름 평균. 248 이 내용 타임라인을 채우기 전에는 빈 배열이다.")
        List<Section> sections) {

    /** 30초 격자 배열. 자기 간격을 함께 갖는다 — 배열마다 격자가 다르므로 응답 최상단에 하나만 둘 수 없다. */
    @Schema(name = "GroupFocusFlow", description = "겹치지 않는 30초 구간의 집중 흐름 계열")
    public record FocusFlow(
            @Schema(description = "칸 크기(초). 겹치지 않는 구간이며 이동창이 아니다.", example = "30")
            int intervalSeconds,

            @Schema(description = "30초 칸 목록. 관측이 한 건도 없으면 빈 배열이다.")
            List<Point> points) {

        /**
         * 30초 칸 하나.
         *
         * <p>{@code focusLevel} 은 <b>1.00~4.00 단계 평균</b>이다. 퍼센트가 아니고 비율도 아니다. {@code null} 은 값 없음이며 0 도 1단계도 아니다 — 카메라를
         * 끈 시간과 낮은 참여도는 다른 사건이다.
         */
        @Schema(name = "GroupFocusFlowPoint", description = "30초 칸. focusLevel 은 1.00~4.00 이고 null 은 값 없음이다.")
        public record Point(
                @Schema(description = "칸의 시작 시각(초)", example = "300")
                long offsetSeconds,

                @Schema(
                        description = "집중 흐름(1.00~4.00 단계 평균). 4단계 판정이 칸의 70% 를 못 덮거나 eligibleCount 가 5 미만이면"
                                + " null 이며 0 도 1단계도 아니다.",
                        example = "3.67",
                        nullable = true)
                Double focusLevel,

                @Schema(description = "그 칸에 걸친 5초 스냅샷 eligibleCount 의 최솟값. 칸 안에서 인원이 흔들리면 적었던 순간을 따른다.", example = "28")
                int eligibleCount) {

            private static Point from(GroupFocusBucket bucket) {
                return new Point(bucket.offsetSeconds(), bucket.focusLevel(), bucket.eligibleCount());
            }
        }

        private static FocusFlow from(GetGroupAttentionTimelineResult result) {
            return new FocusFlow(
                    result.focusIntervalSeconds(),
                    result.focusBuckets().stream().map(Point::from).toList());
        }
    }

    /** 5초 격자 배열. */
    @Schema(name = "GroupSignals", description = "5초 격자의 집단 신호 비율 계열")
    public record Signals(
            @Schema(description = "점 사이 간격(초)", example = "5")
            int intervalSeconds,

            @Schema(description = "5초 격자 점 목록. 관측이 한 건도 없으면 빈 배열이다.")
            List<Point> points) {

        /**
         * 격자 한 점.
         *
         * <p>모든 {@code *Ratio} 는 <b>0.0~1.0 분수</b>다. 표시할 때만 100 을 곱한다. {@code focusLevel} 과 단위가 다르다. {@code null} 은 값
         * 없음이며 절대 0 이 아니다.
         */
        // 학생 응답의 중첩 레코드도 Point 라, 이름을 지정하지 않으면 springdoc 이 두 스키마를 하나로 합친다.
        // 합쳐지면 생성된 문서가 강사 응답도 학생 필드를 갖는 것처럼 말하게 된다.
        @Schema(name = "GroupSignalPoint", description = "격자 한 점. 모든 비율은 0.0~1.0 분수이고 null 은 값 없음이다.")
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

        private static Signals from(GetGroupAttentionTimelineResult result) {
            return new Signals(
                    result.signalIntervalSeconds(),
                    result.signalPoints().stream().map(Point::from).toList());
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

    /** 수업 내용 구간 하나. 10분 같은 고정 길이가 아니라 248 이 찾아낸 실제 경계다. */
    @Schema(name = "GroupSectionFocus", description = "수업 내용 구간별 집중 흐름 평균")
    public record Section(
            @Schema(description = "구간 시작 초", example = "0") long startSeconds,

            @Schema(description = "구간 종료 초", example = "372")
            long endSeconds,

            @Schema(description = "구간 제목", example = "함수의 정의")
            String title,

            @Schema(
                    description = "구간 안 30초 칸 값들의 단순 평균(1.00~4.00). 값이 하나도 없으면 null.",
                    example = "3.21",
                    nullable = true)
            Double focusLevel) {

        private static Section from(SectionFocusAverage average) {
            return new Section(average.startSeconds(), average.endSeconds(), average.title(), average.focusLevel());
        }
    }

    public static GroupAttentionTimelineResponse from(GetGroupAttentionTimelineResult result) {
        return new GroupAttentionTimelineResponse(
                result.durationSeconds(),
                FocusFlow.from(result),
                Signals.from(result),
                result.distractedIntervals().stream().map(Interval::from).toList(),
                result.sections().stream().map(Section::from).toList());
    }
}
