package com.a105.zani.audioclip.application.ingestclip;

import java.time.Instant;

/** 클립 안에서 실제로 캡처가 이뤄진 연속 구간(벽시계 기준). 음소거 공백의 반대 개념이다. */
public record CapturedSegment(Instant from, Instant to) {}
