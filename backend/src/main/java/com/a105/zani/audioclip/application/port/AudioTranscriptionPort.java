package com.a105.zani.audioclip.application.port;

import java.io.InputStream;

/**
 * 오디오 스트림을 텍스트로 전사하는 포트.
 *
 * <p>구현은 스트림을 디스크에 기록해서는 안 된다 — "전사가 끝나면 오디오를 즉시 폐기한다"는 요구사항의 가장 강한 형태는 애초에 저장하지 않는 것이다. 실패는 애플리케이션 예외로 변환해 던진다.
 */
public interface AudioTranscriptionPort {

    String transcribe(InputStream audio, String contentType);
}
