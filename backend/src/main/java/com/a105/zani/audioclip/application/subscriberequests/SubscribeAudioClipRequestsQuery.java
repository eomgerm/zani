package com.a105.zani.audioclip.application.subscriberequests;

/** 강사가 자기 세션의 클립 요청 스트림을 구독한다. */
public record SubscribeAudioClipRequestsQuery(Long sessionId, Long userId) {}
