package com.a105.zani.audioclip.domain.model;

/** 오디오 클립 요청의 처리 상태. 만료는 시각으로 판단하는 파생 상태라 여기 포함하지 않는다. */
public enum AudioClipRequestStatus {
    PENDING,
    UPLOADED,
    FAILED
}
