package com.a105.zani.postclass;

import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.domain.model.TranscriptDocumentSegment;
import com.a105.zani.recording.domain.model.TrackSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 문서 세그먼트의 불변식을 검증한다.
 *
 * <p>이 record 는 <b>쓰기와 읽기의 공통 관문</b>이다. 조립이 실수해도, 옛 판의 문서를 읽어도, 손으로 넣은 시드가 틀려도 같은 자리에서 걸려야 한다. 그래서 조립 테스트와 별도로 둔다 — 조립
 * 경로를 거치지 않는 입력도 막히는지 확인해야 한다.
 */
class TranscriptDocumentSegmentTest {

    private static final long PARTICIPANT = 9_400_101L;
    private static final long FILE = 9_400_201L;

    private static TranscriptDocumentSegment segment(
            long startOffsetMs, long endOffsetMs, String text, double avgLogprob, double confidence, double noSpeech) {
        return new TranscriptDocumentSegment(
                PARTICIPANT,
                TrackSource.MICROPHONE,
                startOffsetMs,
                endOffsetMs,
                text,
                avgLogprob,
                confidence,
                noSpeech,
                FILE,
                0);
    }

    private static TranscriptDocumentSegment valid() {
        return segment(1_000, 5_000, "정상 문장", -0.21, Math.exp(-0.21), 0.02);
    }

    @Test
    void 정상_값은_그대로_받는다() {
        TranscriptDocumentSegment segment = valid();

        assertEquals(1_000, segment.startOffsetMs());
        assertEquals(5_000, segment.endOffsetMs());
        assertEquals(TrackSource.MICROPHONE, segment.source());
    }

    @Test
    void 시작과_종료가_같은_순간은_허용한다() {
        // 아주 짧은 발화는 밀리초 해상도에서 같은 값으로 떨어질 수 있다. 뒤집힌 것과는 다르다.
        assertEquals(3_000, segment(3_000, 3_000, "네", -0.21, 0.81, 0.02).endOffsetMs());
    }

    @Test
    void 종료가_시작보다_앞서면_거절한다() {
        // 종료를 청크 경계로 줄이는 로직이 시작 시각을 함께 보지 않으면 이런 구간이 만들어진다.
        // 어떤 소비자도 올바르게 해석할 수 없다.
        assertThrows(IllegalArgumentException.class, () -> segment(600_500, 600_000, "뒤집힌 구간", -0.21, 0.81, 0.02));
    }

    @Test
    void 음수_시작_시각은_거절한다() {
        assertThrows(IllegalArgumentException.class, () -> segment(-1, 5_000, "문장", -0.21, 0.81, 0.02));
    }

    @Test
    void 텍스트가_없으면_거절한다() {
        assertThrows(IllegalArgumentException.class, () -> segment(0, 1_000, null, -0.21, 0.81, 0.02));
    }

    @Test
    void 유한하지_않은_확률값은_거절한다() {
        // NaN·Infinity 는 JSON 표준 값이 아니라 직렬화가 깨지거나 표준을 벗어난 문서를 만들고,
        // 하류의 평균·비교 계산을 조용히 오염시킨다.
        assertThrows(IllegalArgumentException.class, () -> segment(0, 1_000, "문장", Double.NaN, 0.81, 0.02));
        assertThrows(
                IllegalArgumentException.class, () -> segment(0, 1_000, "문장", Double.NEGATIVE_INFINITY, 0.81, 0.02));
        assertThrows(IllegalArgumentException.class, () -> segment(0, 1_000, "문장", -0.21, Double.NaN, 0.02));
        assertThrows(IllegalArgumentException.class, () -> segment(0, 1_000, "문장", -0.21, 0.81, Double.NaN));
    }

    @Test
    void 양수_로그확률은_거절한다() {
        // 로그 확률은 0 을 넘을 수 없다. 넘었다면 GMS 응답을 잘못 읽은 것이다.
        assertThrows(IllegalArgumentException.class, () -> segment(0, 1_000, "문장", 0.5, 0.81, 0.02));
    }

    @Test
    void 범위를_벗어난_신뢰도와_무음_확률은_거절한다() {
        assertThrows(IllegalArgumentException.class, () -> segment(0, 1_000, "문장", -0.21, 1.5, 0.02));
        assertThrows(IllegalArgumentException.class, () -> segment(0, 1_000, "문장", -0.21, -0.1, 0.02));
        assertThrows(IllegalArgumentException.class, () -> segment(0, 1_000, "문장", -0.21, 0.81, 1.5));
        assertThrows(IllegalArgumentException.class, () -> segment(0, 1_000, "문장", -0.21, 0.81, -0.1));
    }

    @Test
    void 크게_음수인_로그확률의_언더플로_신뢰도는_허용한다() {
        // exp(-800) 은 0.0 으로 언더플로한다. 계산 결과이므로 막으면 정상 응답이 거절된다.
        assertEquals(0.0, segment(0, 1_000, "아주 불확실한 문장", -800, 0.0, 0.9).confidence());
    }

    @Test
    void 식별자와_트랙_종류가_없으면_거절한다() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TranscriptDocumentSegment(
                        0, TrackSource.MICROPHONE, 0, 1_000, "문장", -0.21, 0.81, 0.02, FILE, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TranscriptDocumentSegment(
                        PARTICIPANT, TrackSource.MICROPHONE, 0, 1_000, "문장", -0.21, 0.81, 0.02, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TranscriptDocumentSegment(
                        PARTICIPANT, TrackSource.MICROPHONE, 0, 1_000, "문장", -0.21, 0.81, 0.02, FILE, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TranscriptDocumentSegment(PARTICIPANT, null, 0, 1_000, "문장", -0.21, 0.81, 0.02, FILE, 0));
    }
}
