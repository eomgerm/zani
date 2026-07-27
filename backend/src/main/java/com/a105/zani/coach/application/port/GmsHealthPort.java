package com.a105.zani.coach.application.port;

/**
 * SSAFY GMS(외부 시스템) 도달 가능 여부를 확인하는 포트. (S15P11A105-202)
 *
 * <p>구현(infrastructure)은 최소 비용 프로브 1회로 자격증명·GMS 장애·크레딧 소진을 감지하며, 실패·timeout·비인증은 예외 없이 {@code false}로 흡수한다. 벤더 타입·예외를 이
 * 포트 밖으로 노출하지 않는다.
 *
 * <p>한계: GMS 전체 도달 가능성만 확인한다. whisper-1(203)·팁 모델(204) 개별 장애는 감지하지 못하며, 그 실패는 각 UseCase 가 흡수한다.
 */
public interface GmsHealthPort {

    boolean isGmsReachable();
}
