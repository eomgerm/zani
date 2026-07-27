package com.a105.zani.recording.application.port;

/** Track Egress 시작 결과. egressId는 LiveKit이 발급한 실행 식별자다. */
public record IssuedTrackEgress(String egressId) {}
