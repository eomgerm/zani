package com.a105.zani.session.application.screenshare;

/** 화면 공유 시작 입력. userId는 인증 주체에서, sessionId는 경로에서 온다. */
public record StartScreenShareCommand(long sessionId, long userId) {}
