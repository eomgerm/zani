package com.a105.zani.attention.domain.model.timeline;

import java.util.ArrayList;
import java.util.List;

/**
 * 수업 내용 구간마다 그 안의 30초 칸 값을 단순 평균한다(설계 문서 §2.12).
 *
 * <p>평균의 평균이므로 30초 칸마다 가중치가 같다. 학생 3명만 값이 있던 칸과 28명이 있던 칸이 동등하게 취급된다 — 원본 판정을 전부 모아 평균하는 대안이 더 정확하지만 그러면 집단 평균을 거치지 않는
 * 별도 계산 경로가 하나 더 생긴다. 표시용 보조 수치라 단순한 쪽을 골랐다.
 *
 * <p>칸 값만 받는다. 개인 경로는 {@code FocusBucket}, 강사 경로는 {@code GroupFocusBucket} 에서 값을 뽑아 넘긴다 — 두 타입을 다 받는 오버로드를 만들지 않고 규칙을
 * 하나로 둔다.
 */
public final class SectionFocusCalculator {

    private SectionFocusCalculator() {}

    /** @param bucketLevels 30초 칸 값을 <b>순서대로</b> 담은 목록. 인덱스 {@code i} 가 {@code [i×30, (i+1)×30)} 초에 대응한다 */
    public static List<SectionFocusAverage> calculate(
            List<SectionBoundary> sections, List<Double> bucketLevels, TimelinePolicy policy) {
        long bucketSeconds = policy.focusBucket().toSeconds();

        List<SectionFocusAverage> averages = new ArrayList<>();
        for (SectionBoundary section : sections) {
            List<Double> values = new ArrayList<>();
            for (int i = 0; i < bucketLevels.size(); i++) {
                long bucketStart = i * bucketSeconds;
                // 칸은 자기 시작 시각이 속한 section 에만 들어간다. 걸침 비율로 쪼개 가중하지 않는다 —
                // 결정적이고, 칸 하나가 두 section 에 이중으로 세어지지 않는다.
                if (bucketStart < section.startSeconds() || bucketStart >= section.endSeconds()) {
                    continue;
                }
                Double value = bucketLevels.get(i);
                if (value != null) {
                    values.add(value);
                }
            }
            Double average = values.isEmpty()
                    ? null
                    : FocusLevels.roundToTwo(values.stream()
                            .mapToDouble(Double::doubleValue)
                            .average()
                            .orElseThrow());
            averages.add(
                    new SectionFocusAverage(section.startSeconds(), section.endSeconds(), section.title(), average));
        }
        return List.copyOf(averages);
    }
}
