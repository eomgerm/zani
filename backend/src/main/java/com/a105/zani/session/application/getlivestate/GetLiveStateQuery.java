package com.a105.zani.session.application.getlivestate;

/** 재입장·재연결 후 현재 상태를 복원하기 위한 입력. */
public record GetLiveStateQuery(Long sessionId, Long userId) {}
