package com.a105.zani.audioclip.domain.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * raw PCM 앞에 WAV(RIFF) 헤더를 붙인다.
 *
 * <p>Egress 가 주는 s16le 는 컨테이너가 없어 그대로는 디코더가 형식을 알 수 없다. 44바이트 헤더만 붙이면 바로 디코딩 가능한 파일이 되므로 ffmpeg 같은 외부 변환이 필요 없다.
 */
public final class WavEncoder {

    /** 표준 PCM WAV 헤더 크기(확장 필드 없음). */
    public static final int HEADER_BYTES = 44;

    private static final short PCM_FORMAT_CODE = 1;

    private WavEncoder() {}

    public static byte[] encode(byte[] pcm, PcmAudioFormat format) {
        ByteBuffer out = ByteBuffer.allocate(HEADER_BYTES + pcm.length).order(ByteOrder.LITTLE_ENDIAN);

        out.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        // RIFF 크기는 이 필드 다음부터 끝까지 = 전체 - 8.
        out.putInt(HEADER_BYTES + pcm.length - 8);
        out.put("WAVE".getBytes(StandardCharsets.US_ASCII));

        out.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        out.putInt(16); // PCM fmt 청크 크기
        out.putShort(PCM_FORMAT_CODE);
        out.putShort((short) format.channels());
        out.putInt(format.sampleRate());
        out.putInt(format.bytesPerSecond());
        out.putShort((short) format.frameBytes());
        out.putShort((short) format.bitsPerSample());

        out.put("data".getBytes(StandardCharsets.US_ASCII));
        out.putInt(pcm.length);
        out.put(pcm);

        return out.array();
    }
}
