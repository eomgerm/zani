package com.a105.zani.attention.domain.model.timeline;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SectionFocusCalculatorTest {

    private static final TimelinePolicy POLICY = TimelinePolicy.defaults();

    @Test
    @DisplayName("section 안 칸 값들의 단순 평균이다")
    void averages_the_buckets_inside_a_section() {
        List<SectionFocusAverage> averages = SectionFocusCalculator.calculate(
                List.of(new SectionBoundary(0L, 90L, "함수의 정의")), List.of(2.0d, 3.0d, 4.0d), POLICY);

        assertThat(averages).containsExactly(new SectionFocusAverage(0L, 90L, "함수의 정의", 3.0d));
    }

    @Test
    @DisplayName("경계가 30초 배수가 아니면 칸은 자기 시작 시각이 속한 section 으로 간다")
    void a_bucket_belongs_to_the_section_containing_its_start() {
        // 경계 72초. 칸은 0·30·60·90 에서 시작한다.
        // 60초 칸은 [60,90) 이라 경계를 넘지만 시작이 72 미만이므로 첫 section 이다.
        List<SectionFocusAverage> averages = SectionFocusCalculator.calculate(
                List.of(new SectionBoundary(0L, 72L, "앞"), new SectionBoundary(72L, 120L, "뒤")),
                List.of(1.0d, 1.0d, 1.0d, 4.0d),
                POLICY);

        assertThat(averages.get(0).focusLevel()).isEqualTo(1.0d); // 0·30·60 칸
        assertThat(averages.get(1).focusLevel()).isEqualTo(4.0d); // 90 칸
    }

    @Test
    @DisplayName("빈 값 칸은 평균에서 뺀다")
    void null_buckets_are_dropped() {
        List<SectionFocusAverage> averages = SectionFocusCalculator.calculate(
                List.of(new SectionBoundary(0L, 90L, "함수의 정의")), Arrays.asList(4.0d, null, 2.0d), POLICY);

        // null 을 1단계로 채웠다면 2.33 이 됐을 것이다.
        assertThat(averages.get(0).focusLevel()).isEqualTo(3.0d);
    }

    @Test
    @DisplayName("section 안 값이 하나도 없으면 그 section 평균도 빈 값이다")
    void a_section_without_values_is_null() {
        List<SectionFocusAverage> averages = SectionFocusCalculator.calculate(
                List.of(new SectionBoundary(0L, 90L, "함수의 정의")), Arrays.asList(null, null, null), POLICY);

        assertThat(averages.get(0).focusLevel()).isNull();
    }

    @Test
    @DisplayName("section 이 없으면 빈 목록이다 — 248 미완 세션")
    void no_sections_yields_an_empty_list() {
        assertThat(SectionFocusCalculator.calculate(List.of(), List.of(3.0d), POLICY))
                .isEmpty();
    }

    @Test
    @DisplayName("어느 section 에도 속하지 않는 칸은 버린다")
    void buckets_outside_every_section_are_ignored() {
        // section 이 0~30 뿐인데 칸은 0·30·60 세 개다.
        List<SectionFocusAverage> averages = SectionFocusCalculator.calculate(
                List.of(new SectionBoundary(0L, 30L, "앞")), List.of(4.0d, 1.0d, 1.0d), POLICY);

        assertThat(averages.get(0).focusLevel()).isEqualTo(4.0d);
    }
}
