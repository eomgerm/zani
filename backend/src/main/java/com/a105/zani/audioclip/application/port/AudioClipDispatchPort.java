package com.a105.zani.audioclip.application.port;

import com.a105.zani.audioclip.domain.model.AudioClipRequest;

/** 생성된 클립 요청을 해당 세션의 강사 클라이언트에게 전달하는 포트. 전달 실패는 치명적이지 않다 — 요청 자체는 저장소에 남아 재구독 리플레이로 회복된다. */
public interface AudioClipDispatchPort {

    void dispatch(AudioClipRequest request);
}
