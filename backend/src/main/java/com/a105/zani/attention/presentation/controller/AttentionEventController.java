package com.a105.zani.attention.presentation.controller;

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

import com.a105.zani.attention.application.collect.CollectAttentionEventCommand;
import com.a105.zani.attention.application.collect.CollectAttentionEventResult;
import com.a105.zani.attention.application.collect.CollectAttentionEventUseCase;
import com.a105.zani.attention.presentation.request.AttentionEventRequest;
import com.a105.zani.attention.presentation.response.AttentionEventResponse;
import com.a105.zani.common.response.ApiResponse;

@Tag(name = "참여도 판정", description = "학생 브라우저가 만든 참여 상태 판정을 수집한다")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class AttentionEventController {

    private final CollectAttentionEventUseCase collectAttentionEventUseCase;

    @Operation(summary = "참여도 판정 이벤트 전송", description = """
                    학생 브라우저가 10초 창을 분석해 만든 판정 1건을 보낸다. 판정 자체는 브라우저에서 끝나며, 서버는 결과 상태만 받는다.
                    영상 프레임·얼굴 랜드마크·blendshape는 보내지 않는다(계약에 없는 필드가 오면 400으로 거절한다).

                    같은 clientEventId로 다시 보내면 상태를 중복 반영하지 않고 duplicate=true로 성공 응답한다.
                    네트워크 실패 후 재시도할 때는 새 값을 만들지 말고 같은 clientEventId를 그대로 쓰면 된다.

                    수집된 상태는 Redis에만 짧게 보관되며(현재 상태 30초), 강사 코칭 트리거의 5분 관찰 창에 반영된다.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "판정 반영 또는 중복 무시"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "필수 값 누락·범위 위반, 또는 계약에 없는 필드가 포함됨"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "해당 세션의 멤버가 아니거나, 판정을 보낼 수 없는 역할(강사)임"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "세션을 찾을 수 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "이미 종료된 세션. 클라이언트는 전송을 멈춘다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503",
                description = "판정 상태 저장소(Redis) 사용 불가. 코칭만 멈추고 수업은 계속한다.")
    })
    @PostMapping("/{sessionId}/attention-events")
    public ApiResponse<AttentionEventResponse> collect(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "세션 ID") @PathVariable Long sessionId,
            @Valid @RequestBody AttentionEventRequest request) {
        CollectAttentionEventResult result = collectAttentionEventUseCase.collect(new CollectAttentionEventCommand(
                sessionId,
                Long.parseLong(jwt.getSubject()),
                request.type(),
                request.startedAt(),
                request.endedAt(),
                request.durationSec(),
                request.signalQuality(),
                request.clientEventId()));
        return ApiResponse.success(AttentionEventResponse.from(result));
    }
}
