package com.a105.zani.session.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.a105.zani.common.response.ApiResponse;
import com.a105.zani.session.application.screenshare.StartScreenShareCommand;
import com.a105.zani.session.application.screenshare.StartScreenShareResult;
import com.a105.zani.session.application.screenshare.StartScreenShareUseCase;
import com.a105.zani.session.application.screenshare.StopScreenShareCommand;
import com.a105.zani.session.application.screenshare.StopScreenShareUseCase;
import com.a105.zani.session.presentation.response.ScreenShareResponse;

@Tag(name = "화면 공유", description = "세션당 활성 공유 1명 규칙을 서버가 강제한다(역할 제한·승인 플로우 없음)")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class ScreenShareController {

    private final StartScreenShareUseCase startScreenShareUseCase;
    private final StopScreenShareUseCase stopScreenShareUseCase;

    @Operation(
            summary = "화면 공유 시작(활성 슬롯 획득)",
            description =
                    "활성 공유자가 없거나 이미 요청자면 슬롯을 획득/갱신한다. FE는 200을 받은 뒤에만 LiveKit 화면 트랙을 publish하고, 공유 중에는 TTL 갱신을 위해 이 요청을 주기적으로 다시 보낸다. 다른 참가자가 공유 중이면 409로 거부한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "공유 슬롯 획득/갱신"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "해당 세션의 멤버가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "세션을 찾을 수 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "이미 종료된 세션이거나 다른 참가자가 공유 중"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503",
                description = "활성 공유 상태 저장소(Redis) 사용 불가")
    })
    @PostMapping("/{sessionId}/screen-share")
    public ApiResponse<ScreenShareResponse> start(
            @AuthenticationPrincipal Jwt jwt, @Parameter(description = "세션 ID") @PathVariable Long sessionId) {
        StartScreenShareResult result =
                startScreenShareUseCase.start(new StartScreenShareCommand(sessionId, Long.parseLong(jwt.getSubject())));
        return ApiResponse.success(ScreenShareResponse.from(result));
    }

    @Operation(
            summary = "화면 공유 종료(활성 슬롯 반납)",
            description = "요청자가 활성 공유자면 슬롯을 비운다. 공유 중지·강의실 퇴장 시 호출한다. 이미 다른 참가자가 공유 중이면 아무 것도 하지 않는다(멱등).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "슬롯 반납(또는 반납할 것 없음)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "해당 세션의 멤버가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503",
                description = "활성 공유 상태 저장소(Redis) 사용 불가")
    })
    @DeleteMapping("/{sessionId}/screen-share")
    public ApiResponse<Void> stop(
            @AuthenticationPrincipal Jwt jwt, @Parameter(description = "세션 ID") @PathVariable Long sessionId) {
        stopScreenShareUseCase.stop(new StopScreenShareCommand(sessionId, Long.parseLong(jwt.getSubject())));
        return ApiResponse.success();
    }
}
