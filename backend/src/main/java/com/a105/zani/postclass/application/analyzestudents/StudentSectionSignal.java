package com.a105.zani.postclass.application.analyzestudents;

import java.util.List;

/**
 * 구간 하나로 접은 학생 관측. 본문 크기가 관측 수가 아니라 구간 수에만 비례하게 만드는 것이 목적이다.
 *
 * <p>{@code unmeasurableCount} 는 {@code UNMEASURABLE} 과 {@code CAMERA_OFF} 를 합친 값이다 — 요약에서 둘의 대응이 같다.
 * {@code DETECTOR_UNAVAILABLE} 은 학생의 참여가 아니라 기기 문제라 어느 칸에도 넣지 않는다.
 */
public record StudentSectionSignal(
        int sectionIndex,
        int engagedCount,
        int lowEngagementCount,
        int unmeasurableCount,
        int okCount,
        int confusedCount,
        int missedCount,
        int noResponseCount,
        int handRaisedCount,
        int chatCount,
        List<String> chatExcerpts) {}
