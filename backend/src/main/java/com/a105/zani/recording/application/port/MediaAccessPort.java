package com.a105.zani.recording.application.port;

import java.time.Instant;

/**
 * 녹화 접근 자격의 발급과 검증. S3 presigned URL 을 대신하는 자체 서명이다(FRD §4.2·§15.2, S3 미사용).
 *
 * <p>브라우저 {@code <video>} 는 Authorization 헤더를 실을 수 없어 자격이 주소 안에 들어가야 한다. 그래서 발급과 검증을 한 포트가 갖는다 — 서명 알고리즘·키·유효 기간·주소 형식이
 * 모두 한 구현에 모여야 양쪽이 어긋나지 않는다({@code AudioStreamEndpointPort} 선례).
 */
public interface MediaAccessPort {

    /** 이 세션의 녹화를 볼 수 있는 단기 주소. 유효 기간은 구현이 정한다. 호출 전에 권한을 확인하는 것은 호출자 몫이다. */
    IssuedMediaUrl issue(long sessionId);

    /**
     * 이 세션의 썸네일을 볼 수 있는 단기 주소. 경로만 다르고 자격은 녹화와 같은 것을 쓴다 — 썸네일은 녹화에서 잘라낸 한 프레임이라 열람 자격을 나눌 실익이 없고, 나누면 검증 쪽도 종류를 알아야 한다.
     */
    IssuedMediaUrl issueThumbnail(long sessionId);

    /**
     * 들어온 자격이 <b>이 세션의</b> 유효한 자격인지. 서명 일치와 만료 여부를 함께 본다.
     *
     * <p>둘을 나누지 않는 이유는 호출자가 할 일이 같기 때문이다 — 위조든 만료든 응답은 401 이고, 클라이언트는 주소를 다시 발급받는다. 어느 쪽인지 알려주면 공격자에게 유효한 서명을 찾았다는 신호만
     * 준다.
     */
    boolean matches(long sessionId, Instant expiresAt, String token);
}
