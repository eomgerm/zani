package com.a105.zani.session.presentation.request;

import java.time.Instant;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.session.domain.model.ConnectionState;

@Schema(description = "presence heartbeat 요청")
public record PresenceHeartbeatRequest(
        @Schema(
                description = "클라이언트 heartbeat 발생 시각(UTC)",
                example = "2026-07-24T12:30:00Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Instant heartbeatAt,

        @Schema(description = "현재 연결 상태", example = "CONNECTED", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull ConnectionState connectionState) {}
