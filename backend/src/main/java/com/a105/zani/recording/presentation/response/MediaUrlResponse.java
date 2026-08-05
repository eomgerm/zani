package com.a105.zani.recording.presentation.response;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.recording.application.issuemediaurl.IssueMediaUrlResult;

@Schema(description = "권한 검증을 마친 단기 녹화 접근 주소")
public record MediaUrlResponse(
        @Schema(description = "재생에 바로 쓸 수 있는 주소. 자격이 질의 문자열에 포함되어 있다")
        String mediaUrl,

        @Schema(description = "이 주소가 만료되는 시각(UTC). 이후 요청은 401 이며 다시 발급받아야 한다")
        Instant expiresAt) {

    public static MediaUrlResponse from(IssueMediaUrlResult result) {
        return new MediaUrlResponse(result.mediaUrl(), result.expiresAt());
    }
}
