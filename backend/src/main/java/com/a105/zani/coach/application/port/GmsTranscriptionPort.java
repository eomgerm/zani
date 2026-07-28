package com.a105.zani.coach.application.port;

import java.util.Optional;

/**
 * 강사 오디오를 텍스트로 전사하는 포트. (S15P11A105-203)
 *
 * <p>구현(infrastructure)은 GMS whisper-1 을 1회 호출한다. timeout 초과·자격증명 오류·크레딧 소진·서버 오류는 예외를 던지지 않고 빈 결과로 흡수한다. 팁을 보내지 않고 수업은
 * 유지하는 것이 정책이기 때문이다(COACH-003).
 *
 * <p>벤더 응답 타입과 HTTP 예외를 이 포트 밖으로 노출하지 않는다.
 */
public interface GmsTranscriptionPort {

    /** @return 전사 결과. 실패·timeout 이면 {@link Optional#empty()} */
    Optional<TranscriptResult> transcribe(AudioClip clip);
}
