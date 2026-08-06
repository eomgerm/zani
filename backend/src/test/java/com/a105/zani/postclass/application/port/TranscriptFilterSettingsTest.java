package com.a105.zani.postclass.application.port;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 무음 환각 판정 규칙(S15P11A105-306).
 *
 * <p>실측값을 그대로 쓴다 — 환각 8건은 {@code 0.906~0.985}, 실제 질문은 {@code 0.176} 이었다.
 */
class TranscriptFilterSettingsTest {

    private static final TranscriptFilterSettings DEFAULTS = new TranscriptFilterSettings(true, 0.8, true);

    @Test
    void 임곗값보다_높으면_뺀다() {
        assertTrue(DEFAULTS.exceedsNoSpeechThreshold(0.953));
        assertTrue(DEFAULTS.exceedsNoSpeechThreshold(0.984));
        assertTrue(DEFAULTS.exceedsNoSpeechThreshold(0.906));
    }

    @Test
    void 임곗값과_같으면_뺀다() {
        // 경계는 포함이다. 0.8 로 두었는데 0.8 이 남으면 설정의 뜻이 흐려진다.
        assertTrue(DEFAULTS.exceedsNoSpeechThreshold(0.8));
    }

    @Test
    void 임곗값보다_낮으면_남긴다() {
        assertFalse(DEFAULTS.exceedsNoSpeechThreshold(0.176));
        assertFalse(DEFAULTS.exceedsNoSpeechThreshold(0.79));
        assertFalse(DEFAULTS.exceedsNoSpeechThreshold(0.0));
    }

    @Test
    void 껐으면_아무것도_빼지_않는다() {
        TranscriptFilterSettings off = TranscriptFilterSettings.disabled();

        assertFalse(off.exceedsNoSpeechThreshold(0.999));
        assertFalse(off.exceedsNoSpeechThreshold(1.0));
    }

    @Test
    void 앵커는_임곗값_미만인_세그먼트다() {
        // 앵커를 "남은 세그먼트" 로 두면 환각 두 개가 서로를 붙잡아 살아남는다.
        assertTrue(DEFAULTS.anchors(0.176));
        assertTrue(DEFAULTS.anchors(0.79));
        assertFalse(DEFAULTS.anchors(0.8));
        assertFalse(DEFAULTS.anchors(0.964));
    }

    @Test
    void 맞물린_시각만_인접으로_본다() {
        // 실측에서 창 경계로 쪼개진 문장의 두 조각은 정확히 0ms 로 맞물렸다(217.28s).
        assertTrue(DEFAULTS.adjacent(217_280, 217_280));
        assertTrue(DEFAULTS.adjacent(217_280, 217_480), "허용폭 200ms 경계");
        assertFalse(DEFAULTS.adjacent(217_280, 217_481));
        // 28.3초 공백 뒤의 환각. 이것을 인접으로 보면 필터가 무력해진다.
        assertFalse(DEFAULTS.adjacent(218_940, 247_280));
    }

    @Test
    void 종결_부호로_끝나면_문장이_이어지지_않는다() {
        // 재현 응답에서 질문이 있던 자리. 물음표로 끝나므로 그 뒤에 0ms 로 붙은 "고맙습니다." 는 연속이
        // 아니다. 실제 발화 원문은 쓰지 않는다 — 판정에 쓰이는 것은 마지막 글자뿐이다.
        assertFalse(DEFAULTS.sentenceContinues("조회 성능이 어떻게 달라지는지 다시 설명해 주실 수 있나요?"));
        assertFalse(DEFAULTS.sentenceContinues("네, 여기까지 오늘 강의 마치도록 하겠습니다."));
        assertFalse(DEFAULTS.sentenceContinues("고맙습니다."));
        assertFalse(DEFAULTS.sentenceContinues("정말요!"));
        assertFalse(DEFAULTS.sentenceContinues("그러니까…"));
        // 전각 형태. 빠뜨리면 문장이 끝났는데도 이어지는 것으로 보아 환각을 살린다.
        assertFalse(DEFAULTS.sentenceContinues("끝났습니다。"));
        assertFalse(DEFAULTS.sentenceContinues("맞나요？"));
    }

    @Test
    void 종결_부호가_없으면_문장이_이어진다() {
        // 실측된 강사의 진짜 연속. 창 경계에서 잘린 앞부분이라 종결 부호가 없다.
        assertTrue(DEFAULTS.sentenceContinues(" 다엑스트라 알고리즘은 가장 가까운 정점을 선택하고 간선완화연산"));
        // 쉼표·가운뎃점은 연결 부호다. 종결로 취급하면 진짜 연속을 잃는다.
        assertTrue(DEFAULTS.sentenceContinues("간선 완화 연산을 반복하면,"));
        assertTrue(DEFAULTS.sentenceContinues("체이닝·개방 주소법"));
    }

    @Test
    void 앞_텍스트가_비었으면_이어지는_것으로_보지_않는다() {
        // 판단 근거가 없을 때는 살리지 않는다 — 근거 없이 남기면 환각이 통과한다.
        assertFalse(DEFAULTS.sentenceContinues(null));
        assertFalse(DEFAULTS.sentenceContinues(""));
        assertFalse(DEFAULTS.sentenceContinues("   "));
    }

    @Test
    void 겹친_구간도_인접으로_본다() {
        // 뒤 세그먼트가 앞 세그먼트보다 먼저 시작하면 공백이 음수다. 겹침은 발화가 이어진다는 신호이므로
        // 인접으로 본다 — 부호를 놓치면 겹친 구간에서 실제 발화를 잃는다.
        assertTrue(DEFAULTS.adjacent(217_280, 216_000));
    }

    @Test
    void 임곗값이_범위를_벗어나면_만들_수_없다() {
        // 확률과 비교하는 값이라 0~1 밖에서는 뜻이 없다. 여기서 막지 않으면 "1.5 로 두었는데 아무것도
        // 안 빠진다" 같은 조용한 오설정이 남는다.
        assertThrows(IllegalArgumentException.class, () -> new TranscriptFilterSettings(true, -0.1, true));
        assertThrows(IllegalArgumentException.class, () -> new TranscriptFilterSettings(true, 1.1, true));
        assertThrows(IllegalArgumentException.class, () -> new TranscriptFilterSettings(true, Double.NaN, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TranscriptFilterSettings(true, Double.POSITIVE_INFINITY, true));
    }

    @Test
    void 꺼진_설정도_임곗값을_검증한다() {
        // 켜는 날 처음 터지면 그때는 그 값을 누가 왜 넣었는지 아는 사람이 없다.
        assertThrows(IllegalArgumentException.class, () -> new TranscriptFilterSettings(false, 2.0, false));
    }

    @Test
    void 경계값은_유효하다() {
        assertEquals(0.0, new TranscriptFilterSettings(true, 0.0, true).noSpeechThreshold(), 1e-9);
        assertEquals(1.0, new TranscriptFilterSettings(true, 1.0, true).noSpeechThreshold(), 1e-9);
    }
}
