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

    /**
     * 대상 참가자의 화면 공유 트랙을 끈다. 화면 오디오({@code SCREEN_SHARE_AUDIO})는 건드리지 않는다.
     *
     * <p><b>강사 제어가 아니라 단일성 강제용이다.</b> 세션당 활성 공유는 하나이고(FRD §10.2), 발급된 JWT 는 폐기할 수 없어 이미 올라온 트랙을 멈추려면 서버가 미디어 서버에 mute 를
     * 보내는 수밖에 없다. 강사가 남의 공유를 중지시키는 동작은 범위 밖이다(2026-07-30 확정 — 강사 제어는 강제 음소거만).
     *
     * @param identity 미디어 서버가 아는 참가자 식별자({@code p-{participantId}})
     */
    MediaMuteChange muteScreenShare(long sessionId, String identity);
}
