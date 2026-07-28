package com.a105.zani.audioclip.application.port;

import java.util.Optional;

/**
 * 세션별 강사 마이크의 최근 구간 오디오를 메모리에 유지하는 포트.
 *
 * <p>Egress WebSocket 스트림이 채우고, 코칭 트리거가 읽는다. 디스크·DB 어디에도 저장하지 않으며 세션이 끝나면 반납한다.
 */
public interface InstructorAudioBufferPort {

    /** 지금 확보된 오디오. 아직 아무것도 없으면 비어 있다. 호출해도 버퍼는 비우지 않는다. */
    Optional<CapturedAudio> capture(long sessionId);

    /** 지금 확보된 오디오의 재생 시간(ms). 전사 최소 길이 판정에 쓴다. */
    long availableMs(long sessionId);

    /** 세션의 버퍼를 비우고 메모리를 반납한다(수업 종료·스트림 종료). */
    void release(long sessionId);
}
