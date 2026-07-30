package com.a105.zani.attention.domain.model.timeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DistractionIntervalDetectorTest {

    private static final TimelinePolicy POLICY = TimelinePolicy.defaults();

    /** 확인 필요 비율만 있는 점 목록을 만든다. null 은 판단 불가 구간이다. */
    private static List<GroupTimelinePoint> points(Double... ratios) {
        List<GroupTimelinePoint> points = new ArrayList<>();
        for (int i = 0; i < ratios.length; i++) {
            points.add(new GroupTimelinePoint(i * 5L, 10, 10, ratios[i], 0.0d, null, null, null, null));
        }
        return points;
    }

    private static Double[] repeat(double value, int count) {
        Double[] values = new Double[count];
        Arrays.fill(values, value);
        return values;
    }

    private static Double[] nulls(int count) {
        return new Double[count];
    }

    private static Double[] concat(Double[]... groups) {
        List<Double> all = new ArrayList<>();
        for (Double[] group : groups) {
            all.addAll(Arrays.asList(group));
        }
        return all.toArray(new Double[0]);
    }

    @Test
    @DisplayName("30% 이상이 20초 지속되면 시작한다 — 15초로는 시작하지 않는다")
    void starts_after_twenty_seconds_above_the_threshold() {
        // 5초 간격이므로 20초 = 연속 5개 점(0,5,10,15,20).
        assertThat(DistractionIntervalDetector.detect(points(repeat(0.35d, 4)), POLICY))
                .isEmpty();
        assertThat(DistractionIntervalDetector.detect(points(repeat(0.35d, 5)), POLICY))
                .containsExactly(new DistractionInterval(0L, 20L));
    }

    @Test
    @DisplayName("20% 미만이 30초 유지되면 종료한다")
    void ends_after_thirty_seconds_below_the_threshold() {
        List<GroupTimelinePoint> series = points(concat(repeat(0.35d, 5), repeat(0.10d, 7)));

        // 25초부터 임계 미만이 이어져 50초에서 30초를 채운다. 구간은 그 연속의 첫 점인 25초에서 닫힌다 —
        // 아래 merges_intervals_closer_than_fifteen_seconds 와 같은 규칙이다.
        assertThat(DistractionIntervalDetector.detect(series, POLICY))
                .containsExactly(new DistractionInterval(0L, 25L));
    }

    @Test
    @DisplayName("두 구간의 간격이 15초 미만이면 병합한다")
    void merges_intervals_closer_than_fifteen_seconds() {
        // 30% 로 20초 열고 → 10% 로 30초 유지해 25초에서 닫고 → 다시 30% 로 20초 연다.
        List<GroupTimelinePoint> series = points(concat(
                repeat(0.35d, 5), // 0~20 구간이 열린다
                repeat(0.10d, 6), // 25~50, 30초 유지되어 25초에서 닫힌다
                repeat(0.35d, 5))); // 55~75

        // 닫힌 시각 25초, 다음 시작 55초 → 간격 30초. 병합하지 않는다.
        assertThat(DistractionIntervalDetector.detect(series, POLICY))
                .containsExactly(new DistractionInterval(0L, 25L), new DistractionInterval(55L, 75L));
    }

    @Test
    @DisplayName("간격이 15초 미만이면 하나로 합친다")
    void merges_when_the_gap_is_under_fifteen_seconds() {
        // 앞 구간이 25초에 닫히고 뒤 구간이 35초에 시작하면 간격 10초라 합쳐진다.
        List<DistractionInterval> merged = DistractionIntervalDetector.mergeAdjacent(
                List.of(new DistractionInterval(0L, 25L), new DistractionInterval(35L, 60L)), POLICY);

        assertThat(merged).containsExactly(new DistractionInterval(0L, 60L));
    }

    @Test
    @DisplayName("간격이 정확히 15초면 병합하지 않는다 — 경계는 미만이다")
    void keeps_intervals_exactly_fifteen_seconds_apart() {
        List<DistractionInterval> merged = DistractionIntervalDetector.mergeAdjacent(
                List.of(new DistractionInterval(0L, 25L), new DistractionInterval(40L, 60L)), POLICY);

        assertThat(merged).containsExactly(new DistractionInterval(0L, 25L), new DistractionInterval(40L, 60L));
    }

    @Test
    @DisplayName("null 구간은 진행 중인 구간을 끝내지 않고 누적을 멈춘다")
    void null_ratios_suspend_the_counters() {
        // 30% 20초로 열린 뒤 null 이 60초 이어지고 다시 30% 가 온다.
        List<GroupTimelinePoint> series = points(concat(repeat(0.35d, 5), nulls(12), repeat(0.35d, 5)));

        // null 을 0% 로 봤다면 여기서 종료됐을 것이다. 하나로 이어져야 한다.
        assertThat(DistractionIntervalDetector.detect(series, POLICY))
                .containsExactly(new DistractionInterval(0L, 105L));
    }

    @Test
    @DisplayName("null 구간은 시작 누적도 멈춘다")
    void null_ratios_do_not_accumulate_the_start_condition() {
        // 30% 가 10초, null 10초, 다시 30% 10초. 이어 세면 20초라 열리지만 열리면 안 된다.
        List<GroupTimelinePoint> series = points(concat(repeat(0.35d, 2), nulls(2), repeat(0.35d, 2)));

        assertThat(DistractionIntervalDetector.detect(series, POLICY)).isEmpty();
    }

    @Test
    @DisplayName("끝까지 임계 이상이면 마지막 점에서 구간을 닫는다")
    void an_open_interval_closes_at_the_last_point() {
        List<GroupTimelinePoint> series = points(repeat(0.35d, 9)); // 0~40초

        assertThat(DistractionIntervalDetector.detect(series, POLICY))
                .containsExactly(new DistractionInterval(0L, 40L));
    }

    @Test
    @DisplayName("점이 없으면 구간도 없다")
    void an_empty_series_has_no_intervals() {
        assertThat(DistractionIntervalDetector.detect(List.of(), POLICY)).isEmpty();
    }
}
