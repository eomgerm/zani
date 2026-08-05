package com.a105.zani.recording.application.streammedia;

import java.time.Instant;

/**
 * 재생 요청에 실려 온 자격. 인증 주체가 없는 요청이라 이 세 값이 판단 근거 전부다.
 *
 * <p>{@code expiresAt} 도 서명 대상이다. 서명 없이 주소에만 있으면 클라이언트가 값을 늘려 무기한 접근한다.
 */
public record StreamMediaQuery(Long sessionId, Instant expiresAt, String token) {}
