package com.a105.zani.audioclip.application.releaseaudio;

/**
 * 세션의 강사 오디오 버퍼를 반납한다.
 *
 * <p>버퍼는 세션당 창 길이만큼의 바이트 배열을 통째로 잡고 있어, 반납하지 않으면 수업이 끝나도 메모리가 계속 남는다. 수업 종료가 유일한 반납 시점이다 — Egress 스트림 종료는 장치 전환으로도
 * 발생하므로 수업 중간에 버퍼를 날리는 신호로 쓸 수 없다.
 */
public interface ReleaseInstructorAudioUseCase {

    void release(long sessionId);
}
