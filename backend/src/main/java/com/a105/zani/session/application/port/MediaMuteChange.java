package com.a105.zani.session.application.port;

/**
 * 미디어 서버에 강제 음소거를 요청한 결과.
 *
 * <p><b>{@code UNCHANGED} 와 {@code UNAVAILABLE} 을 갈라야 하는 이유는 손들기({@link RaisedHandChange})와 같다.</b> 둘을 뭉치면 미디어 서버가 죽어
 * 아무것도 못 바꾼 요청을 "이미 음소거였다"로 착각해 전 참가자에게 음소거됐다고 알리게 된다. 그러면 <b>화면에는 음소거인데 실제로는 소리가 나가는</b> 상태가 된다 — 손들기가 어긋나는 것보다 나쁘다.
 */
public enum MediaMuteChange {

    /** 실제로 음소거됐다. 이력을 남기고 알린다. */
    CHANGED,

    /** 이미 음소거 상태였다. 이력은 늘리지 않되 알림은 보낸다 — 앞선 알림을 놓친 화면을 맞춰 준다. */
    UNCHANGED,

    /** 대상에게 켜져 있는 마이크 트랙이 없다. 음소거할 것이 없으므로 성립한 것으로 본다. */
    NO_ACTIVE_TRACK,

    /** 미디어 서버를 쓰지 못해 아무것도 바꾸지 못했다. 알리지 않는다. */
    UNAVAILABLE
}
