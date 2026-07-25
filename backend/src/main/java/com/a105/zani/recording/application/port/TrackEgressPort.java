package com.a105.zani.recording.application.port;

/** LiveKit Track Egress를 시작하는 포트. 벤더 SDK 타입은 인프라 어댑터 안에만 존재한다. */
public interface TrackEgressPort {

    IssuedTrackEgress start(TrackEgressRequest request);
}
