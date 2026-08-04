package com.a105.zani.recording.application.stoprecording;

/**
 * 세션의 진행 중인 녹화를 멈춘다. 수업 종료가 부르는 녹화 도메인의 공개 계약이다.
 *
 * <p>세션 도메인이 {@code TrackEgressPort} 를 직접 잡지 않게 하려고 둔다 — 녹화의 인프라는 녹화가 소유한다.
 */
public interface StopSessionRecordingUseCase {

    /** @return 이번 호출로 멈춘 Egress 수. 멈출 것이 없거나 미디어 서버를 쓰지 못하면 0 */
    int stopRecording(Long sessionId);
}
