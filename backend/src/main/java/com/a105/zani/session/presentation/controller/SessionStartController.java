package com.a105.zani.session.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.a105.zani.common.response.ApiResponse;
import com.a105.zani.session.application.start.StartSessionCommand;
import com.a105.zani.session.application.start.StartSessionResult;
import com.a105.zani.session.application.start.StartSessionUseCase;
import com.a105.zani.session.presentation.response.StartSessionResponse;

@Tag(name = "세션 시작", description = "강사가 준비를 마치고 수업을 실제로 시작하는 API입니다.")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionStartController {

    private final StartSessionUseCase startSessionUseCase;

    @Operation(summary = "수업 시작 (강사 전용)", description = """
                    준비 화면에서 마이크·카메라 확인을 마친 강사가 누르는 **수업 시작** 버튼이 호출합니다.
                    수업을 연 강사만 호출할 수 있고, 성공하면 세션 상태가 `PREPARING` → `LIVE` 로 바뀝니다.

                    **이 호출 전까지 학생은 들어올 수 없습니다.** 초대 코드는 생성 시점에 발급되지만 `LIVE` 가 되어야 유효해집니다.
                    자동 종료 시각(`expiresAt`, 시작 + 3시간)도 이때 정해집니다.

                    **두 번 눌러도 안전합니다.** 이미 진행 중인 수업에 다시 요청해도 오류가 아니라 `200` 과 함께
                    `started: false` 를 돌려주며, **시작 시각은 뒤로 밀리지 않습니다**(최대 수업 시간이 늘어나지 않습니다).

                    **요청 본문은 없습니다.**
                    """)
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "시작 완료. 이번 요청으로 시작되었으면 `started: true`, 이미 진행 중이었으면 `started: false` 입니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "로그인이 필요합니다. (`COMM_401`)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "수업을 연 강사가 아닙니다. (`SESSION_APP_006`)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "존재하지 않는 세션 ID 입니다. (`SESSION_APP_005`)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "이미 종료된 수업은 다시 시작할 수 없습니다. (`MEDIA_TOKEN_...`/종료 상태 충돌)")
    })
    @PostMapping("/{sessionId}/start")
    public ApiResponse<StartSessionResponse> start(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "시작할 수업의 세션 ID", example = "1234567890") @PathVariable Long sessionId) {
        StartSessionResult result =
                startSessionUseCase.start(new StartSessionCommand(sessionId, Long.parseLong(jwt.getSubject())));
        return ApiResponse.success(StartSessionResponse.from(result));
    }
}
