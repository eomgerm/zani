package com.a105.zani.report.application.askreportquestion;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.a105.zani.report.application.listsessionsections.SessionSectionView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 앵커 → 시간창 규칙을 고정한다.
 *
 * <p>이 계산이 한 구간 밀려도 답변은 여전히 유창하게 나오므로 화면으로는 잡히지 않는다. 경계에서 어느 쪽에 붙는지를 여기서 못박아 두지 않으면, 나중에 누군가 부등호를 뒤집어도 아무도 알아채지 못한다.
 */
class GroundingWindowTest {

    /** 빈틈없이 이어지는 네 구간. 248 이 만드는 타임라인과 같은 모양이다. */
    private static final List<SessionSectionView> SECTIONS = List.of(
            new SessionSectionView(0L, 60_000L, "수업 시작과 주제 안내", "인사와 화면 공유를 했다."),
            new SessionSectionView(60_000L, 95_000L, "해시 테이블과 해시 함수", "키를 인덱스로 바꾸는 과정을 설명했다."),
            new SessionSectionView(95_000L, 140_000L, "해시 충돌 해결", "체이닝과 개방주소법을 다뤘다."),
            new SessionSectionView(140_000L, 185_000L, "적재율과 배열 확장", "적재율이 높아지면 배열을 늘린다고 설명했다."));

    @Test
    void widensToOneSectionOnEachSide() {
        GroundingWindow window = GroundingWindow.resolve(SECTIONS, 120_000L);

        // 3번 구간을 짚었으면 2~4번이 창이다. 경계 바로 앞 문장이 그 주제의 실제 설명인 경우가 흔하다.
        assertEquals("해시 충돌 해결", window.anchor().title());
        assertEquals(60_000L, window.windowFromMs());
        assertEquals(185_000L, window.windowToMs());
    }

    @Test
    void putsABoundaryInstantInTheLaterSection() {
        // 구간이 빈틈없이 이어져 95,000ms 는 2번의 끝이자 3번의 시작이다. 한쪽으로 못박지 않으면
        // 같은 드래그가 실행마다 다른 창을 낸다.
        assertEquals(
                "해시 충돌 해결", GroundingWindow.resolve(SECTIONS, 95_000L).anchor().title());
        assertEquals(
                "해시 테이블과 해시 함수",
                GroundingWindow.resolve(SECTIONS, 94_999L).anchor().title());
    }

    @Test
    void doesNotWidenBeforeTheFirstSection() {
        GroundingWindow window = GroundingWindow.resolve(SECTIONS, 0L);

        // 앞이 없는 쪽으로 넓히려다 음수 인덱스를 만들면 안 된다. 자기 자신에서 시작한다.
        assertEquals("수업 시작과 주제 안내", window.anchor().title());
        assertEquals(0L, window.windowFromMs());
        assertEquals(95_000L, window.windowToMs());
    }

    @Test
    void doesNotWidenPastTheLastSection() {
        GroundingWindow window = GroundingWindow.resolve(SECTIONS, 150_000L);

        assertEquals("적재율과 배열 확장", window.anchor().title());
        assertEquals(95_000L, window.windowFromMs());
        assertEquals(185_000L, window.windowToMs());
    }

    @Test
    void keepsTheClosingInstantOfTheClassInsideTheLastSection() {
        // 248 은 마지막 구간을 classDurationMs 까지 채운다. 반열림으로만 두면 수업의 끝 시각이 어느
        // 구간에도 속하지 않아, 마지막 구간을 드래그한 사람이 앵커 없음으로 떨어진다.
        GroundingWindow window = GroundingWindow.resolve(SECTIONS, 185_000L);

        assertTrue(window.anchored());
        assertEquals("적재율과 배열 확장", window.anchor().title());
    }

    @Test
    void reportsNoAnchorForAnOffsetOutsideEverySection() {
        // 수업 길이를 넘는 값은 위조된 앵커이거나 깨진 계산이다. 어느 쪽이든 가장 가까운 구간으로
        // 끌어다 붙이면 안 된다 — 묻지 않은 곳에 대한 답이 나온다.
        assertFalse(GroundingWindow.resolve(SECTIONS, 200_000L).anchored());
        assertFalse(GroundingWindow.resolve(SECTIONS, -1L).anchored());
    }

    @Test
    void reportsNoAnchorWhenTheDragWasOutsideTheTimeline() {
        // 전체 요약 문단을 드래그하면 좌표가 없다. 오류가 아니라 전 구간 요약으로 답하는 갈래다.
        GroundingWindow window = GroundingWindow.resolve(SECTIONS, null);

        assertFalse(window.anchored());
        assertNull(window.anchor());
    }

    @Test
    void reportsNoAnchorWhenTheSessionHasNoSections() {
        // 248 이 아직 돌지 않은 세션이다. 빈 목록은 정상 상태라 여기서 터지면 안 된다.
        assertFalse(GroundingWindow.resolve(List.of(), 120_000L).anchored());
        assertFalse(GroundingWindow.resolve(null, 120_000L).anchored());
    }

    @Test
    void reportsNoAnchorForAnInstantThatFellInAGapBetweenSections() {
        // 248 이 구간을 이어 붙이지 못한 세션이 실측으로 있었다(내부 공백 10개). 그 틈을 앞 구간으로
        // 끌어다 붙이면 창이 조용히 한 칸 밀린다. 앵커 없음이 안전한 답이다.
        List<SessionSectionView> gapped = List.of(
                new SessionSectionView(0L, 60_000L, "앞", "앞 구간이다."),
                new SessionSectionView(70_000L, 95_000L, "뒤", "뒤 구간이다."));

        assertFalse(GroundingWindow.resolve(gapped, 65_000L).anchored());
    }

    @Test
    void keepsASingleSectionSessionInsideItself() {
        // Mock 어댑터가 구간 하나짜리 세션을 만든다. 양쪽 clamp 가 같은 칸을 가리켜도 창이 깨지지 않아야 한다.
        List<SessionSectionView> only = List.of(new SessionSectionView(0L, 60_000L, "하나뿐", "한 구간이다."));

        GroundingWindow window = GroundingWindow.resolve(only, 30_000L);

        assertEquals(0L, window.windowFromMs());
        assertEquals(60_000L, window.windowToMs());
    }
}
