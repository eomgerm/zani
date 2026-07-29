package com.a105.zani.audioclip.infrastructure.encoding;

import com.a105.zani.audioclip.domain.model.PcmAudioFormat;

/**
 * 링버퍼가 떠낸 raw PCM 을 전사 서비스에 넘길 형식으로 바꾼다.
 *
 * <p>링버퍼에서 분리해 둔 이유가 두 가지다. 하나는 인코딩이 외부 라이브러리를 쓰므로 순수 바이트 조작인 버퍼와 섞지 않는 것이고, 다른 하나는 버퍼가 떠낸 구간을 테스트에서 바이트 단위로 검증할 수 있게
 * 남기는 것이다. MP3 는 손실 압축이라 인코딩을 거치면 원본 PCM 이 그대로 돌아오지 않아, 버퍼가 직접 인코딩하면 "최근 N초를 정확히 떠냈는지"를 검증할 방법이 사라진다.
 */
public interface TranscriptionAudioEncoder {

    /**
     * @param pcm 인코딩할 raw PCM (프레임 경계에 맞춰져 있어야 한다)
     * @param format {@code pcm} 의 샘플레이트·채널·비트수
     */
    byte[] encode(byte[] pcm, PcmAudioFormat format);

    /** 전사 요청에 실어 보낼 MIME 타입. */
    String contentType();
}
