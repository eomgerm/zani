package com.a105.zani.recording.application.port;

import java.time.Instant;

/**
 * 발급된 녹화 접근 주소. 토큰은 주소 안에 들어 있고 따로 저장하지 않는다 — 서명이라 검증할 때 다시 계산한다.
 *
 * <p>주소 형식과 유효 기간은 인프라가 소유한다({@link MediaAccessPort}). 응용 계층은 "얼마나 유효한지"만 받아 응답에 싣는다.
 */
public record IssuedMediaUrl(String url, Instant expiresAt) {}
