package com.a105.zani.session.application.port;

/**
 * LiveKit 미디어 토큰 발급 포트. 벤더(LiveKit) SDK/DTO를 application·domain에 노출하지 않고, 내부 값 객체({@link MediaTokenRequest},
 * {@link IssuedMediaToken})로만 주고받는다.
 */
public interface LiveKitTokenPort {

    IssuedMediaToken issue(MediaTokenRequest request);
}
