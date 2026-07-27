package com.a105.zani.audioclip.application.requestclip;

/** 세션의 강사에게 최근 구간 오디오 클립 업로드를 요청한다. 트리거(그룹 알림·수동 버튼 등)는 호출자가 결정한다. */
public record RequestAudioClipCommand(Long sessionId) {}
