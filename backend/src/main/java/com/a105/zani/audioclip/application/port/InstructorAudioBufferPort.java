package com.a105.zani.audioclip.application.port;

import java.time.Duration;
import java.util.Optional;

/**
 * 세션별 강사 마이크의 최근 구간 오디오를 메모리에 유지하는 포트.
 *
 * <p>Egress WebSocket 스트림이 채우고, 코칭 트리거가 읽는다. 디스크·DB 어디에도 저장하지 않으며 세션이 끝나면 반납한다.
 */
public interface InstructorAudioBufferPort {

    /**
     * 최근 {@code window} 만큼을 전사용 16kHz WAV 로 떠낸다. 확보된 양이 window 보다 적으면 있는 만큼만 담고 {@link AudioClip#actual()} 에 실제 길이를
     * 알린다. 호출해도 버퍼는 비우지 않는다.
     *
     * <p>window 를 파라미터로 둔 이유: 전사에 넘길 구간이 아직 확정되지 않았다. 300초 전체를 보내면 전사 시간이 예산을 넘길 수 있어 호출자가 조절할 수 있어야 한다.
     */
    Optional<AudioClip> snapshot(long sessionId, Duration window);

    /** 지금 확보된 오디오의 재생 시간(ms). 전사 최소 길이 판정에 쓴다. */
    long availableMs(long sessionId);

    /** 세션의 버퍼를 비우고 메모리를 반납한다(수업 종료). */
    void release(long sessionId);
}
