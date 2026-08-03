package com.a105.zani.session.application.togglehand;

/**
 * 손들기 상태 변경 입력.
 *
 * <p>"뒤집어라"가 아니라 <b>원하는 상태</b>를 받는다. 뒤집기로 두면 재시도가 한 번 더 뒤집어 의도와 반대가 된다.
 *
 * @param raised 손을 든 상태로 만들려면 true, 내리려면 false
 */
public record ToggleHandCommand(Long sessionId, Long userId, String clientEventId, boolean raised) {}
