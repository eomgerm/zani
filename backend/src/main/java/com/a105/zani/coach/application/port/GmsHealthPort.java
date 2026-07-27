package com.a105.zani.coach.application.port;

/**
 * SSAFY GMS(외부 시스템)의 실시간 코칭 사용 가능 여부를 확인하는 포트. (S15P11A105-202)
 *
 * <p>구현(infrastructure)은 whisper-1 헬스체크를 수행하며, 실패·timeout·비인증은 예외 없이 {@code false}로 흡수한다. 벤더 타입·예외를 이 포트 밖으로 노출하지 않는다.
 */
public interface GmsHealthPort {

    boolean isWhisperAvailable();
}
