package com.a105.zani.session.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.a105.zani.common.response.ApiResponse;
import com.a105.zani.session.application.issuemediatoken.IssueMediaTokenCommand;
import com.a105.zani.session.application.issuemediatoken.IssueMediaTokenResult;
import com.a105.zani.session.application.issuemediatoken.IssueMediaTokenUseCase;
import com.a105.zani.session.presentation.response.MediaTokenResponse;

@Tag(name = "미디어 토큰", description = "LiveKit room 접속 토큰 발급")
@RestController
@RequestMapping("/api/v1/sessions")
public class MediaTokenController {

    private final IssueMediaTokenUseCase issueMediaTokenUseCase;

    public MediaTokenController(IssueMediaTokenUseCase issueMediaTokenUseCase) {
        this.issueMediaTokenUseCase = issueMediaTokenUseCase;
    }

    @Operation(
            summary = "LiveKit 미디어 토큰 발급",
            description =
                    "인증된 세션 멤버에게 LiveKit room 접속 토큰을 발급한다. identity·표시 이름·역할·grant는 서버가 결정하며 요청 body는 없다. TTL은 10분이다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "발급 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "해당 세션의 멤버가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "세션을 찾을 수 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 종료된 세션")
    })
    @PostMapping("/{sessionId}/media-token")
    public ApiResponse<MediaTokenResponse> issue(
            @AuthenticationPrincipal Jwt jwt, @Parameter(description = "세션 ID") @PathVariable Long sessionId) {
        IssueMediaTokenResult result =
                issueMediaTokenUseCase.issue(new IssueMediaTokenCommand(sessionId, Long.parseLong(jwt.getSubject())));
        return ApiResponse.success(MediaTokenResponse.from(result));
    }
}
