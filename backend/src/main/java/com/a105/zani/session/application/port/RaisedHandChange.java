package com.a105.zani.session.application.port;

/**
 * 손들기 상태를 바꾸려 한 결과.
 *
 * <p><b>{@code UNCHANGED} 와 {@code UNAVAILABLE} 을 반드시 갈라야 한다.</b> 둘 다 "상태가 안 바뀌었다"로 뭉치면, 저장소가 죽어 아무것도 기록하지 못한 요청을 "이미
 * 같은 상태였다" 로 착각해 전 참가자에게 손을 들었다고 알리게 된다. 그러면 화면에는 손이 올라가 있는데 서버는 그 사실을 모르는 상태가 되고, 재연결 스냅샷에서 조용히 사라진다.
 */
public enum RaisedHandChange {

    /** 실제로 바뀌었다. 이력을 남기고 알린다. */
    CHANGED,

    /** 이미 원하는 상태였다. 이력은 늘리지 않되, 알림은 다시 보낸다 — 앞선 알림을 놓친 사람의 복구 경로다. */
    UNCHANGED,

    /** 저장소를 쓰지 못해 아무것도 기록하지 못했다. 알리지 않는다. */
    UNAVAILABLE
}
