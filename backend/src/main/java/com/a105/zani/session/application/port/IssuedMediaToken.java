package com.a105.zani.session.application.port;

import java.time.Instant;

/** 발급된 LiveKit 접속 정보. accessToken은 로그·DB에 저장하지 않는다. */
public record IssuedMediaToken(String liveKitUrl, String accessToken, String roomName, Instant expiresAt) {}
