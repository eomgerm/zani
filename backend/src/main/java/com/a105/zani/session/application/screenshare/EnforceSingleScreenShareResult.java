package com.a105.zani.session.application.screenshare;

/** 발행된 화면 공유 트랙에 대한 단일성 판정. */
public enum EnforceSingleScreenShareResult {

    /**
     * 이 참가자가 활성 공유자다. 발행을 그대로 둔다.
     *
     * <p>판정 근거(활성 공유 슬롯)를 읽지 못한 경우도 여기로 온다. 근거 없이 남의 화면을 끄는 것이 겹친 공유를 잠시 허용하는 것보다 나쁘다.
     */
    ACTIVE,

    /** 다른 참가자가 이미 공유 중이라 이 트랙을 껐다. 호출부는 이 트랙을 없는 것으로 다뤄야 한다. */
    REJECTED
}
