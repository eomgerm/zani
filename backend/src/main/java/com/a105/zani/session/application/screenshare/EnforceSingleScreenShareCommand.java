package com.a105.zani.session.application.screenshare;

/**
 * 방금 화면 공유 트랙을 발행한 참가자.
 *
 * <p>트랙 SID 를 받지 않는다. 참가자의 화면 공유 트랙은 하나뿐이라 미디어 서버에서 source 로 찾을 수 있고, 벤더가 매기는 SID 를 application 경계로 들이면 이 계약이 LiveKit 에
 * 묶인다.
 */
public record EnforceSingleScreenShareCommand(long sessionId, long participantId) {}
