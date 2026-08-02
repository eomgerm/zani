package com.a105.zani.session.application.port;

/**
 * 미디어 서버에 대고 참가자의 발행을 제어한다. 벤더(LiveKit) 타입은 인프라 어댑터 안에만 둔다.
 *
 * <p><b>음소거 해제는 없다.</b> 강사가 남의 마이크를 켤 수 있으면 본인 모르게 소리가 나가기 시작한다. 해제는 학생 본인만 한다(FRD §10.5).
 */
public interface MediaModerationPort {

    /**
     * 대상 참가자의 마이크를 끈다. 이미 꺼져 있으면 그대로 둔다.
     *
     * @param identity 미디어 서버가 아는 참가자 식별자({@code p-{participantId}})
     */
    MediaMuteChange muteMicrophone(long sessionId, String identity);
}
