package com.a105.zani.session.application.screenshare;

/**
 * 실제로 올라온 화면 공유 트랙에 대고 "세션당 하나" 를 강제한다(FRD §10.2).
 *
 * <p><b>{@link StartScreenShareUseCase} 로는 부족하다.</b> 그쪽은 발행 <i>전</i> 악수라, 슬롯을 받은 사람만 publish 한다는 클라이언트의 선의에 기댄다. API 를
 * 부르지 않고 바로 publish 하는 클라이언트는 아무도 막지 못한다 — 발급된 JWT 에 {@code SCREEN_SHARE} 권한이 들어 있고, 그 권한은 폐기할 수
 * 없다(livekit-integration-context §8). 그래서 발행이 끝난 뒤 도착하는 {@code track_published} 웹훅에서 한 번 더 판정한다.
 *
 * <p>호출부는 웹훅이 이미 세션·참가자를 확인한 뒤다. 여기서 멤버십을 다시 보지 않는다.
 */
public interface EnforceSingleScreenShareUseCase {

    EnforceSingleScreenShareResult enforce(EnforceSingleScreenShareCommand command);
}
