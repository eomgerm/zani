package com.a105.zani.recording.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.a105.zani.common.response.ApiResponse;
import com.a105.zani.recording.application.webhook.ProcessRecordingWebhookUseCase;

@Tag(name = "녹화 webhook", description = "LiveKit webhook 수신(서명 검증·이벤트 내구 저장·중복 차단)")
@RestController
@RequiredArgsConstructor
public class RecordingWebhookController {

    private final ProcessRecordingWebhookUseCase processRecordingWebhookUseCase;

    @Operation(
            summary = "LiveKit webhook 수신",
            description =
                    "LiveKit이 전송하는 webhook을 수신한다. 인증은 Authorization 헤더의 LiveKit 서명 토큰으로 하며, event id 기준으로 중복을 차단한다. 처리 실패 시 5xx로 응답해 LiveKit 재전송에서 재처리한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "이벤트 접수"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "서명 불일치"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503",
                description = "처리 준비 미완(예: recordings 행 미커밋) — LiveKit 재전송 대상")
    })
    @PostMapping("/api/v1/internal/recordings/webhook")
    public ApiResponse<Void> receive(
            @RequestBody String body,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        processRecordingWebhookUseCase.process(body, authorizationHeader);
        return ApiResponse.success();
    }
}
