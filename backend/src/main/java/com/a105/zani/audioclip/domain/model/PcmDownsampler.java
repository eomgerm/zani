package com.a105.zani.audioclip.domain.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * s16le PCM 을 전사용 16kHz 로 낮춘다.
 *
 * <p>필요한 이유는 두 가지다. 전사 서비스가 16kHz 를 기대하고, 48kHz 300초는 28.8MB 라 Whisper 계열의 파일 상한(25MB)을 넘는다. 16kHz 로 낮추면 9.6MB 가 된다.
 *
 * <p>단순히 3개마다 하나씩 골라내면 8kHz 위 성분이 가청 대역으로 접혀 들어와(에일리어싱) 원래 없던 소리가 생긴다. 사람 귀에는 잡음이지만 전사기에는 실제 음소처럼 보여 정확도를 떨어뜨리므로, 추출 전에
 * 저역통과 필터를 건다.
 */
public final class PcmDownsampler {

    /** 창 함수를 씌운 sinc FIR 의 탭 수(홀수). 출력 샘플당 곱셈 31회로 부담이 없다. */
    private static final int TAPS = 31;

    /** 차단 주파수. 새 나이키스트(8kHz)보다 낮춰 전이 대역을 확보한다. */
    private static final double CUTOFF_HZ = 7_000;

    private PcmDownsampler() {}

    /**
     * @param pcm s16le raw 바이트
     * @param source 입력 형식. 샘플레이트가 16kHz 의 정수배여야 한다
     * @return 16kHz s16le. 입력이 이미 16kHz 면 그대로 돌려준다
     */
    public static byte[] toTranscriptionRate(byte[] pcm, PcmAudioFormat source) {
        PcmAudioFormat target = PcmAudioFormat.transcription();
        if (source.sampleRate() == target.sampleRate()) {
            return pcm;
        }
        if (source.sampleRate() % target.sampleRate() != 0) {
            throw new IllegalArgumentException(
                    "sample rate must be a multiple of " + target.sampleRate() + ": " + source.sampleRate());
        }
        if (source.channels() != 1) {
            throw new IllegalArgumentException("only mono is supported, got " + source.channels() + " channels");
        }
        if (pcm.length == 0) {
            return pcm;
        }

        int factor = source.sampleRate() / target.sampleRate();
        short[] samples = decode(pcm);
        double[] filter = lowPassFilter(source.sampleRate());

        int outputCount = samples.length / factor;
        ByteBuffer out = ByteBuffer.allocate(outputCount * 2).order(ByteOrder.LITTLE_ENDIAN);
        int half = TAPS / 2;
        for (int i = 0; i < outputCount; i += 1) {
            int center = i * factor;
            double sum = 0;
            for (int tap = 0; tap < TAPS; tap += 1) {
                int index = center + tap - half;
                // 가장자리는 인접 샘플로 확장한다. 0으로 채우면 시작·끝에 클릭음이 생긴다.
                int clamped = Math.min(Math.max(index, 0), samples.length - 1);
                sum += samples[clamped] * filter[tap];
            }
            out.putShort(clamp(sum));
        }
        return out.array();
    }

    /** Hamming 창을 씌운 sinc 저역통과 필터. 계수 합을 1로 맞춰 전체 음량을 보존한다. */
    private static double[] lowPassFilter(int sampleRate) {
        double[] filter = new double[TAPS];
        int half = TAPS / 2;
        double normalizedCutoff = CUTOFF_HZ / sampleRate;
        double sum = 0;
        for (int i = 0; i < TAPS; i += 1) {
            int offset = i - half;
            double sinc = offset == 0
                    ? 2 * normalizedCutoff
                    : Math.sin(2 * Math.PI * normalizedCutoff * offset) / (Math.PI * offset);
            double window = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (TAPS - 1));
            filter[i] = sinc * window;
            sum += filter[i];
        }
        for (int i = 0; i < TAPS; i += 1) {
            filter[i] /= sum;
        }
        return filter;
    }

    private static short[] decode(byte[] pcm) {
        ByteBuffer in = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        short[] samples = new short[pcm.length / 2];
        for (int i = 0; i < samples.length; i += 1) {
            samples[i] = in.getShort();
        }
        return samples;
    }

    private static short clamp(double value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value)));
    }
}
