package com.a105.zani.audioclip.infrastructure.encoding;

import com.a105.zani.audioclip.domain.model.PcmAudioFormat;

/**
 * PCM 을 손대지 않고 돌려주는 테스트용 인코더.
 *
 * <p>링버퍼 테스트는 "최근 N초를 정확히 떠냈는지"를 바이트 단위로 대조한다. 실제 인코더(MP3)는 손실 압축이라 원본 바이트가 돌아오지 않아 그 검증이 불가능하다. 인코딩 자체는
 * {@link Mp3TranscriptionAudioEncoderTest} 가 따로 검증하므로, 버퍼 테스트에서는 이 항등 인코더를 끼워 슬라이싱 로직만 본다.
 */
public final class PassThroughAudioEncoder implements TranscriptionAudioEncoder {

    /** 인코딩하지 않은 raw PCM 임을 드러내는 값. 실제 전사 경로에서는 쓰이지 않는다. */
    public static final String CONTENT_TYPE = "audio/L16";

    @Override
    public byte[] encode(byte[] pcm, PcmAudioFormat format) {
        return pcm;
    }

    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }
}
