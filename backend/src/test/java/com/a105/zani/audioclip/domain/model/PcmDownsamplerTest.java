package com.a105.zani.audioclip.domain.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmDownsamplerTest {

    private static final PcmAudioFormat SOURCE = PcmAudioFormat.liveKitDefault(); // 48kHz
    private static final PcmAudioFormat TARGET = PcmAudioFormat.transcription(); // 16kHz

    /** 진폭 amplitude, 주파수 hz 인 사인파를 s16le 로 만든다. */
    private static byte[] sine(double hz, int amplitude, int samples, int sampleRate) {
        ByteBuffer out = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i += 1) {
            out.putShort((short) (amplitude * Math.sin(2 * Math.PI * hz * i / sampleRate)));
        }
        return out.array();
    }

    /** 앞뒤 전이 구간을 뺀 정상 구간의 최대 진폭. */
    private static int peak(byte[] pcm, int skipSamples) {
        ByteBuffer in = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        int peak = 0;
        for (int i = 0; in.remaining() >= 2; i += 1) {
            int value = Math.abs(in.getShort());
            if (i >= skipSamples) {
                peak = Math.max(peak, value);
            }
        }
        return peak;
    }

    @Test
    void 출력_길이는_정확히_3분의_1이다() {
        byte[] source = sine(440, 10_000, 4_800, 48_000);

        byte[] out = PcmDownsampler.toTranscriptionRate(source, SOURCE);

        assertEquals(source.length / 3, out.length);
        assertEquals(TARGET.durationMsOf(out.length), SOURCE.durationMsOf(source.length));
    }

    @Test
    void 낮은_주파수의_말소리는_거의_그대로_통과한다() {
        // 500Hz는 사람 목소리 기본 주파수대. 감쇠되면 전사 품질이 떨어진다.
        byte[] source = sine(500, 10_000, 9_600, 48_000);

        byte[] out = PcmDownsampler.toTranscriptionRate(source, SOURCE);

        assertTrue(peak(out, 32) > 8_000, "500Hz가 과하게 깎였다: " + peak(out, 32));
    }

    @Test
    void 새_나이키스트를_넘는_고주파는_강하게_감쇠된다() {
        // 15kHz는 16kHz 샘플링의 나이키스트(8kHz) 밖이다. 저역통과 없이 추출하면 1kHz 부근으로
        // 접혀 들어와(에일리어싱) 없는 소리가 생긴다.
        byte[] source = sine(15_000, 10_000, 9_600, 48_000);

        byte[] out = PcmDownsampler.toTranscriptionRate(source, SOURCE);

        assertTrue(peak(out, 32) < 1_000, "고주파가 접혀 들어왔다: " + peak(out, 32));
    }

    @Test
    void 무음은_무음으로_남는다() {
        byte[] out = PcmDownsampler.toTranscriptionRate(new byte[4_800], SOURCE);

        assertEquals(1_600, out.length);
        assertEquals(0, peak(out, 0));
    }

    @Test
    void 직류_성분의_크기를_보존한다() {
        // 필터 계수 합이 1이 아니면 전체 음량이 흔들린다.
        byte[] source = new byte[4_800];
        ByteBuffer in = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        while (in.remaining() >= 2) {
            in.putShort((short) 1_000);
        }

        byte[] out = PcmDownsampler.toTranscriptionRate(source, SOURCE);

        int peak = peak(out, 64);
        assertTrue(peak > 950 && peak < 1_050, "직류 이득이 1에서 벗어났다: " + peak);
    }

    @Test
    void 이미_16kHz면_그대로_돌려준다() {
        byte[] source = sine(500, 10_000, 1_600, 16_000);

        assertEquals(source.length, PcmDownsampler.toTranscriptionRate(source, TARGET).length);
    }

    @Test
    void 정수비가_아닌_샘플레이트는_거부한다() {
        PcmAudioFormat odd = new PcmAudioFormat(44_100, 1, 16);

        assertThrows(IllegalArgumentException.class, () -> PcmDownsampler.toTranscriptionRate(new byte[100], odd));
    }

    @Test
    void 빈_입력은_빈_출력이다() {
        assertEquals(0, PcmDownsampler.toTranscriptionRate(new byte[0], SOURCE).length);
    }
}
