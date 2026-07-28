package com.a105.zani.audioclip.domain.model;

import java.time.Duration;

/**
 * 부호 있는 리틀엔디언 16비트 PCM(s16le)의 형식.
 *
 * <p>LiveKit Track Egress 의 WebSocket 출력이 이 형식으로 raw 프레임을 보낸다(컨테이너 없음). 컨테이너가 없다는 것은 바이트 수와 재생 시간이 정확히 비례한다는 뜻이라, 링버퍼가
 * "최근 N초"를 바이트 산술만으로 정확히 잘라낼 수 있다.
 *
 * @param sampleRate 초당 샘플 수
 * @param channels 채널 수
 * @param bitsPerSample 샘플당 비트 수. s16le 만 다루므로 16이어야 한다
 */
public record PcmAudioFormat(int sampleRate, int channels, int bitsPerSample) {

    private static final int SUPPORTED_BITS_PER_SAMPLE = 16;

    public PcmAudioFormat {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (bitsPerSample != SUPPORTED_BITS_PER_SAMPLE) {
            throw new IllegalArgumentException("only s16le is supported, got " + bitsPerSample + " bits");
        }
    }

    /** LiveKit WebSocket egress 의 기본 출력. 샘플레이트는 들어오는 트랙을 따르며 보통 48kHz 다. */
    public static PcmAudioFormat liveKitDefault() {
        return new PcmAudioFormat(48_000, 1, 16);
    }

    /** 전사 입력 규격(Whisper 계열이 기대하는 16kHz 모노). */
    public static PcmAudioFormat transcription() {
        return new PcmAudioFormat(16_000, 1, 16);
    }

    /** 한 프레임(모든 채널의 한 샘플)의 바이트 수. */
    public int frameBytes() {
        return channels * (bitsPerSample / 8);
    }

    public int bytesPerSecond() {
        return sampleRate * frameBytes();
    }

    /** 바이트 수에 해당하는 재생 시간(ms). 프레임을 이루지 못하는 잔여 바이트는 버린다. */
    public long durationMsOf(long bytes) {
        long frames = bytes / frameBytes();
        return frames * 1_000L / sampleRate;
    }

    /** 주어진 재생 시간을 담는 데 필요한 바이트 수. */
    public long bytesFor(Duration duration) {
        return duration.toMillis() * bytesPerSecond() / 1_000L;
    }
}
