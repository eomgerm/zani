package com.a105.zani.audioclip.presentation.controller;

import jakarta.servlet.http.HttpServletResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.a105.zani.audioclip.application.subscriberequests.SubscribeAudioClipRequestsQuery;
import com.a105.zani.audioclip.application.subscriberequests.SubscribeAudioClipRequestsResult;
import com.a105.zani.audioclip.application.subscriberequests.SubscribeAudioClipRequestsUseCase;
import com.a105.zani.audioclip.infrastructure.sse.AudioClipSseHub;

/**
 * 클립 요청 SSE 스트림. 자격 검증과 리플레이 대상 결정은 UseCase 가 담당하고, 이 컨트롤러는 전송 채널만 연결한다.
 *
 * <p>{@link SseEmitter}는 웹 계층 타입이라 application 계층을 통과할 수 없어(계층 규칙상 HTTP 타입 금지) 전송 허브를 직접 사용한다.
 */
@Tag(name = "오디오 클립", description = "강사 마이크 최근 구간 클립 업로드·실패 보고. 오디오는 전사 후 즉시 폐기된다.")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class AudioClipStreamController {

    private final SubscribeAudioClipRequestsUseCase subscribeUseCase;
    private final AudioClipSseHub sseHub;

    @Operation(
            summary = "오디오 클립 요청 SSE 구독",
            description = "강사 전용. 서버가 클립이 필요할 때 AUDIO_CLIP_REQUESTED 이벤트를 밀어 넣고 20초마다 ping 주석을 보낸다. "
                    + "구독 직후, 연결이 끊긴 사이 쌓인 미만료 PENDING 요청을 즉시 리플레이한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "SSE 스트림 시작"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "해당 세션의 강사가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "세션을 찾을 수 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 종료된 세션"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "요청 저장소(Redis) 사용 불가")
    })
    @GetMapping(value = "/{sessionId}/audio-clip-requests/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "세션 ID") @PathVariable Long sessionId,
            HttpServletResponse response) {
        SubscribeAudioClipRequestsResult result = subscribeUseCase.subscribe(
                new SubscribeAudioClipRequestsQuery(sessionId, Long.parseLong(jwt.getSubject())));

        // Nginx 가 이 응답만 버퍼링하지 않도록 응답 단위로 지시한다(location 설정 변경 불필요).
        response.setHeader("X-Accel-Buffering", "no");
        return sseHub.register(sessionId, result.pendingRequests());
    }
}
