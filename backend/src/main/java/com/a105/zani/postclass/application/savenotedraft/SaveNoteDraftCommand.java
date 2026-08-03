package com.a105.zani.postclass.application.savenotedraft;

/**
 * 강사가 자동 저장으로 보낸 메모 초안.
 *
 * @param content 세션 하나에 대한 메모 본문. 비어 있을 수 있다 — 쓰던 내용을 지운 상태도 유효한 초안이다.
 */
public record SaveNoteDraftCommand(Long sessionId, Long userId, String content) {}
