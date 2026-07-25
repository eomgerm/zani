package com.a105.zani.session.domain.model;

/** 참가자가 heartbeat로 보고하는 실시간 연결 상태. CONNECTED만 "현재 접속 중"으로 취급한다. */
public enum ConnectionState {
    CONNECTED,
    RECONNECTING,
    DISCONNECTED
}
