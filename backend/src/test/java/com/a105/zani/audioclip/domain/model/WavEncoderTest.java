package com.a105.zani.audioclip.domain.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WavEncoderTest {

    private static final PcmAudioFormat FORMAT = PcmAudioFormat.liveKitDefault();

    private static byte[] pcm(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i += 1) {
            bytes[i] = (byte) (i % 128);
        }
        return bytes;
    }

    private static String ascii(byte[] wav, int offset) {
        return new String(wav, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int intAt(byte[] wav, int offset) {
        return ByteBuffer.wrap(wav, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static int shortAt(byte[] wav, int offset) {
        return ByteBuffer.wrap(wav, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
    }

    @Test
    void 헤더는_44바이트이고_PCM_뒤에_그대로_붙는다() {
        byte[] audio = pcm(1_000);

        byte[] wav = WavEncoder.encode(audio, FORMAT);

        assertEquals(44 + audio.length, wav.length);
        assertArrayEquals(audio, java.util.Arrays.copyOfRange(wav, 44, wav.length));
    }

    @Test
    void RIFF_WAVE_fmt_data_청크_표식이_규격대로다() {
        byte[] wav = WavEncoder.encode(pcm(100), FORMAT);

        assertEquals("RIFF", ascii(wav, 0));
        assertEquals("WAVE", ascii(wav, 8));
        assertEquals("fmt ", ascii(wav, 12));
        assertEquals("data", ascii(wav, 36));
    }

    @Test
    void 크기_필드는_실제_데이터_길이를_가리킨다() {
        byte[] audio = pcm(1_000);

        byte[] wav = WavEncoder.encode(audio, FORMAT);

        // RIFF 크기 = 전체 - 8, data 크기 = PCM 길이.
        assertEquals(wav.length - 8, intAt(wav, 4));
        assertEquals(audio.length, intAt(wav, 40));
        // fmt 청크 크기 16, PCM 포맷 코드 1.
        assertEquals(16, intAt(wav, 16));
        assertEquals(1, shortAt(wav, 20));
    }

    @Test
    void 형식_필드가_PcmAudioFormat과_일치한다() {
        byte[] wav = WavEncoder.encode(pcm(100), FORMAT);

        assertEquals(FORMAT.channels(), shortAt(wav, 22));
        assertEquals(FORMAT.sampleRate(), intAt(wav, 24));
        assertEquals(FORMAT.bytesPerSecond(), intAt(wav, 28));
        assertEquals(FORMAT.frameBytes(), shortAt(wav, 32));
        assertEquals(FORMAT.bitsPerSample(), shortAt(wav, 34));
    }

    @Test
    void 다른_샘플레이트도_그대로_반영한다() {
        PcmAudioFormat transcription = PcmAudioFormat.transcription();

        byte[] wav = WavEncoder.encode(pcm(100), transcription);

        assertEquals(16_000, intAt(wav, 24));
        assertEquals(32_000, intAt(wav, 28));
    }
}
