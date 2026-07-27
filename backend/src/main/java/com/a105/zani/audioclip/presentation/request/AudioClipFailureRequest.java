package com.a105.zani.audioclip.presentation.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import com.a105.zani.audioclip.domain.model.AudioClipFailureReason;

/** 클립을 확보하지 못한 사유 보고. availableMs 는 보고 시점의 가용 캡처 시간이다. */
public record AudioClipFailureRequest(
        @NotNull AudioClipFailureReason reason,
        @NotNull @PositiveOrZero Long availableMs) {}
