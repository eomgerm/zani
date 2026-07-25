package com.a105.zani.session.presentation.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.a105.zani.common.response.ApiResponse;
import com.a105.zani.session.application.presence.PresenceResult;
import com.a105.zani.session.application.presence.RecordPresenceCommand;
import com.a105.zani.session.application.presence.RecordPresenceUseCase;
import com.a105.zani.session.presentation.request.PresenceHeartbeatRequest;
import com.a105.zani.session.presentation.response.SessionPresenceResponse;

@Tag(name = "세션 presence", description = "실시간 heartbeat·재연결·강사 유예 처리")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionPresenceController {

    private final RecordPresenceUseCase recordPresenceUseCase;

    @Operation(
            summary = "presence heartbeat 전송",
            description =
                    "인증된 세션 멤버의 heartbeat를 기록한다. 강사가 이탈하면 5분 유예가 시작되고, 유예가 지난 뒤 도착한 heartbeat가 세션을 종료한다. presence TTL·유예는 서버가 관리하며 요청 시각은 참고값이다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "presence 반영"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "해당 세션의 멤버가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "세션을 찾을 수 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 종료된 세션"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503",
                description = "presence 저장소(Redis) 사용 불가")
    })
    @PostMapping("/{sessionId}/presence")
    public ApiResponse<SessionPresenceResponse> heartbeat(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "세션 ID") @PathVariable Long sessionId,
            @Valid @RequestBody PresenceHeartbeatRequest request) {
        PresenceResult result = recordPresenceUseCase.record(new RecordPresenceCommand(
                sessionId, Long.parseLong(jwt.getSubject()), request.heartbeatAt(), request.connectionState()));
        return ApiResponse.success(SessionPresenceResponse.from(result));
    }
}
