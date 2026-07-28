package com.a105.zani.recording.application.port;

/**
 * 오디오 트랙을 WebSocket 으로 실시간 전달하는 Egress 시작 요청.
 *
 * <p>파일 저장 Egress 와 별개의 실행이다. LiveKit proto 의 출력이 oneof 라 한 Egress 가 파일과 WebSocket 을 동시에 낼 수 없어, 같은 트랙에 두 Egress 를
 * 띄운다(녹화용 + 실시간 소비용).
 *
 * @param sessionId 대상 세션
 * @param trackSid 대상 트랙(오디오만 가능)
 */
public record AudioStreamEgressRequest(Long sessionId, String trackSid) {}
