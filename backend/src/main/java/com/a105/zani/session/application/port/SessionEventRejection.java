package com.a105.zani.session.application.port;

/**
 * 전송이 받아들여지지 않았다는 통지. 보낸 사람에게만 간다.
 *
 * @param clientEventId 어느 전송이 실패했는지 알려 주는 키. 이게 없으면 클라이언트가 낙관적으로 그려 둔 여러 항목 중 무엇을 실패로 표시할지 알 수 없다.
 * @param reason 화면에 그대로 띄우지 않고 클라이언트가 문구를 고르는 데 쓰는 코드
 */
public record SessionEventRejection(String clientEventId, String reason) {}
