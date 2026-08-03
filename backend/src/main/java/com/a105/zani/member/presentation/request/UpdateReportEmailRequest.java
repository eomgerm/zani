package com.a105.zani.member.presentation.request;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "강의 리포트 완료 이메일 수신 설정 변경 요청")
public record UpdateReportEmailRequest(
        @Schema(description = "리포트 완료 이메일 수신 여부. true 면 수신, false 면 발송 제외", example = "true")
        @NotNull(message = "reportEmailEnabled 는 필수입니다.") Boolean reportEmailEnabled) {}
