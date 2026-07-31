package com.a105.zani.session.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.a105.zani.common.response.ApiResponse;
import com.a105.zani.session.application.getlivestate.GetLiveStateQuery;
import com.a105.zani.session.application.getlivestate.GetLiveStateUseCase;
import com.a105.zani.session.presentation.response.LiveStateResponse;

@Tag(name = "수업 실시간 상태", description = "재연결 직후 채팅 이력·손들기 현황을 되돌린다")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class LiveStateController {

    private final GetLiveStateUseCase getLiveStateUseCase;

    @Operation(summary = "실시간 상태 스냅샷", description = """
                    STOMP 로 재연결한 직후 한 번 호출한다. 최근 공개 채팅과 지금 손을 든 참가자를 함께 준다.

                    호출 순서가 중요하다 — 세션 주제를 **먼저 구독하고** 그다음 이 스냅샷을 받아야 한다. 순서를 뒤집으면
                    두 호출 사이에 도착한 메시지가 어디에도 담기지 않는다. 스냅샷과 스트림이 겹치는 구간은
                    eventId 로 걸러낸다.

                    참가자 디렉터리를 함께 주는 이유: 채팅 이력에는 이미 퇴장한 사람의 메시지가 남아 있어,
                    LiveKit 참가자 목록만으로는 발신자 이름을 채울 수 없다.

                    이메일 등 개인 식별 정보는 담기지 않는다(표시 이름만).""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "현재 상태"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "해당 세션의 멤버가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "세션을 찾을 수 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 종료된 세션")
    })
    @GetMapping("/{sessionId}/live-state")
    public ApiResponse<LiveStateResponse> liveState(
            @AuthenticationPrincipal Jwt jwt, @Parameter(description = "세션 ID") @PathVariable Long sessionId) {
        return ApiResponse.success(LiveStateResponse.from(
                getLiveStateUseCase.get(new GetLiveStateQuery(sessionId, Long.parseLong(jwt.getSubject())))));
    }
}
