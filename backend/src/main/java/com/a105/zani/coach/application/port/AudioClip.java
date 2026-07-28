package com.a105.zani.coach.application.port;

import java.time.Duration;
import java.time.Instant;

/**
 * 강사 오디오 링버퍼에서 잘라낸 구간. 서버 메모리에만 존재하고 파일로 남기지 않는다. (S15P11A105-198 계약)
 *
 * <p>공급자(198)가 16kHz mono 로 다운샘플한 뒤 mp3 로 인코딩해 전달한다. 48kHz 무압축은 300초에 28.8MB 로 whisper 파일 상한(25MB)을 넘고, mp3 는 같은 구간이 약
 * 2.3MB 라 업로드가 가볍다.
 *
 * @param mp3 mp3 바이트. 파일 경로가 아니라 메모리 바이트다
 * @param from 구간 시작 시각
 * @param to 구간 종료 시각
 * @param actual 실제 확보된 길이. 요청한 window 보다 짧을 수 있고, 최소 길이 판정에 쓴다
 */
public record AudioClip(byte[] mp3, Instant from, Instant to, Duration actual) {

    /** byte[] 를 그대로 출력하지 않는다(오디오 바이트가 로그로 새지 않게). */
    @Override
    public String toString() {
        return "AudioClip[bytes=%d, from=%s, to=%s, actual=%s]"
                .formatted(mp3 == null ? 0 : mp3.length, from, to, actual);
    }
}
