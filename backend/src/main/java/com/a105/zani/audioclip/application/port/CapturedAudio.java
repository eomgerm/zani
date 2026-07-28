package com.a105.zani.audioclip.application.port;

import com.a105.zani.audioclip.domain.model.PcmAudioFormat;

/**
 * 링버퍼에서 떠낸 최근 구간 오디오. 컨테이너 없는 raw PCM 이라 전사 어댑터가 필요한 형식(WAV 등)으로 감싸 쓴다.
 *
 * @param pcm s16le raw 바이트. 어디에도 저장하지 않고 전사 후 폐기한다
 * @param format pcm 을 해석하는 데 필요한 샘플레이트·채널 정보
 * @param durationMs pcm 의 재생 시간
 */
public record CapturedAudio(byte[] pcm, PcmAudioFormat format, long durationMs) {}
