package com.a105.zani.attention.application.getattentiontimeline;

import java.util.List;

import com.a105.zani.attention.domain.model.timeline.SectionBoundary;
import com.a105.zani.report.application.listsessionsections.SessionSectionView;

/**
 * 내용 구간 조회 결과를 도메인 경계 타입으로 옮긴다.
 *
 * <p>{@code SessionSectionView} 의 오프셋은 <b>밀리초</b>이고 {@code SectionBoundary} 는 <b>초</b>다. 변환을 빠뜨리면 모든 칸의 시작 초가 첫 section
 * 의 밀리초 범위 안에 들어가 전부 첫 구간으로 몰린다. 두 유스케이스가 같은 변환을 각자 적으면 한쪽만 틀리므로 여기 한 곳에 둔다.
 */
final class SectionBoundaries {

    private SectionBoundaries() {}

    static List<SectionBoundary> from(List<SessionSectionView> views) {
        return views.stream()
                .map(view -> new SectionBoundary(
                        view.startedOffsetMs() / 1000L, view.endedOffsetMs() / 1000L, view.title(), view.summary()))
                .toList();
    }
}
