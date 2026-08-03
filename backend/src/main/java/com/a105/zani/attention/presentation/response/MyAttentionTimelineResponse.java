package com.a105.zani.attention.presentation.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.attention.application.getattentiontimeline.GetMyAttentionTimelineResult;
import com.a105.zani.attention.domain.model.timeline.FocusBucket;
import com.a105.zani.attention.domain.model.timeline.SectionFocusAverage;
import com.a105.zani.attention.domain.model.timeline.StateInterval;
import com.a105.zani.attention.domain.model.timeline.StudentTimelineState;

/**
 * 학생 본인의 집중 흐름 응답.
 *
 * <p>평균 점수·타인 비교·모델 확률을 담지 않는다(REPORT-S-010). 자기 점수를 남과 견주게 만드는 순간 이 지표는 학습을 돕는 것이 아니라 등수가 된다. 단계 값 자체는 준다 — FRD §11.7
 * 의 단계 비노출 제한은 실시간 화면에만 적용된다.
 *
 * <p>신호 비율 배열이 없다. 확인 필요 비율은 집단 값이라 개인 화면에 의미가 없고, 내려보내면 타인 정보가 된다.
 */
@Schema(description = "종료된 수업에서 본인의 집중 흐름. 평균·타인 비교·모델 내부 값은 담기지 않는다.")
public record MyAttentionTimelineResponse(
        @Schema(description = "타임라인이 덮는 길이(초). 수업 길이가 아니라 본인 관측이 있는 마지막 시각까지다. 관측이 없으면 0.", example = "5400")
        long durationSeconds,

        @Schema(description = "집중 흐름. 겹치지 않는 30초 구간의 4단계 평균이다.")
        FocusFlow focusFlow,

        @Schema(description = "상태 구간 목록. 인접 동일 상태는 서버가 합쳐서 준다. 관측이 없던 시간은 구간을 만들지 않아 사이가 빌 수 있다.")
        List<Interval> stateIntervals,

        @Schema(description = "수업 내용 구간별 집중 흐름 평균. 248 이 내용 타임라인을 채우기 전에는 빈 배열이다.")
        List<Section> sections) {

    /** 30초 격자 배열. 상태 구간과 격자를 공유하지 않는다. */
    @Schema(name = "MyFocusFlow", description = "겹치지 않는 30초 구간의 집중 흐름 계열")
    public record FocusFlow(
            @Schema(description = "칸 크기(초). 겹치지 않는 구간이며 이동창이 아니다.", example = "30")
            int intervalSeconds,

            @Schema(description = "30초 칸 목록. 관측이 한 건도 없으면 빈 배열이다.")
            List<Point> points) {

        /**
         * 30초 칸 하나.
         *
         * <p>{@code focusLevel} 은 <b>1.00~4.00 단계 평균</b>이며 강사 응답과 같은 척도다. 퍼센트가 아니다. {@code null} 은 값 없음이며 0 도 1단계도 아니다.
         */
        // 강사 응답의 중첩 레코드도 Point 라 이름을 갈라 둔다. 이유는 GroupAttentionTimelineResponse 에 적었다.
        @Schema(name = "MyFocusFlowPoint", description = "30초 칸. focusLevel 은 1.00~4.00 이고 null 은 값 없음이다.")
        public record Point(
                @Schema(description = "칸의 시작 시각(초)", example = "300")
                long offsetSeconds,

                @Schema(
                        description = "집중 흐름(1.00~4.00 단계 평균). 4단계 판정이 칸의 70% 를 못 덮으면 null 이며 0 도 1단계도 아니다."
                                + " 확인 질문에 '헷갈려요' 로 답해도 이 값은 떨어지지 않는다.",
                        example = "3.67",
                        nullable = true)
                Double focusLevel) {

            private static Point from(FocusBucket bucket) {
                return new Point(bucket.offsetSeconds(), bucket.focusLevel());
            }
        }

        private static FocusFlow from(GetMyAttentionTimelineResult result) {
            return new FocusFlow(
                    result.focusIntervalSeconds(),
                    result.focusBuckets().stream().map(Point::from).toList());
        }
    }

    /** 상태 구간 하나. FE 는 병합하지 않고 받은 대로 그린다. */
    @Schema(name = "MyStateInterval", description = "상태 구간. GOOD 도 포함해 관측이 있던 시간을 덮는다.")
    public record Interval(
            @Schema(description = "시작 초", example = "300") long startSeconds,
            @Schema(description = "종료 초", example = "340") long endSeconds,

            @Schema(
                    description = "그 구간의 상태. CHECK_NEEDED 는 헷갈려요·놓쳤어요·무응답을 묶은 값이며 학생 화면은 셋을 구분하지 않는다.",
                    example = "CHECK_NEEDED")
            StudentTimelineState state) {

        private static Interval from(StateInterval interval) {
            return new Interval(interval.startSeconds(), interval.endSeconds(), interval.state());
        }
    }

    /** 수업 내용 구간 하나. 평균은 본인 칸 값으로 낸 것이며 타인 비교가 아니다. */
    @Schema(name = "MySectionFocus", description = "수업 내용 구간별 집중 흐름 평균")
    public record Section(
            @Schema(description = "구간 시작 초", example = "0") long startSeconds,

            @Schema(description = "구간 종료 초", example = "372")
            long endSeconds,

            @Schema(description = "구간 제목", example = "함수의 정의")
            String title,

            @Schema(
                    description = "구간 안 30초 칸 값들의 단순 평균(1.00~4.00). 값이 하나도 없으면 null.",
                    example = "3.44",
                    nullable = true)
            Double focusLevel) {

        private static Section from(SectionFocusAverage average) {
            return new Section(average.startSeconds(), average.endSeconds(), average.title(), average.focusLevel());
        }
    }

    public static MyAttentionTimelineResponse from(GetMyAttentionTimelineResult result) {
        return new MyAttentionTimelineResponse(
                result.durationSeconds(),
                FocusFlow.from(result),
                result.stateIntervals().stream().map(Interval::from).toList(),
                result.sections().stream().map(Section::from).toList());
    }
}
