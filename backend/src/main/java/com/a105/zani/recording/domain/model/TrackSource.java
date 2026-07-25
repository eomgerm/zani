package com.a105.zani.recording.domain.model;

/** 녹화 대상 LiveKit Track의 source 종류. manifest schema v1의 `source` enum과 값이 일치해야 한다. */
public enum TrackSource {
    CAMERA,
    MICROPHONE,
    SCREEN_SHARE,
    SCREEN_SHARE_AUDIO
}
