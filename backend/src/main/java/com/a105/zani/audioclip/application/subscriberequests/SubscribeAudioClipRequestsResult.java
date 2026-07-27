package com.a105.zani.audioclip.application.subscriberequests;

import java.util.List;

import com.a105.zani.audioclip.domain.model.AudioClipRequest;

/** 구독 자격 검증 결과. pendingRequests 는 구독 직후 즉시 재전달(리플레이)할, 아직 만료되지 않은 PENDING 요청들이다 — SSE 연결이 끊긴 사이에 생성된 요청이 유실되지 않게 한다. */
public record SubscribeAudioClipRequestsResult(List<AudioClipRequest> pendingRequests) {}
