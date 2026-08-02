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
import com.a105.zani.session.application.moderation.MuteParticipantCommand;
import com.a105.zani.session.application.moderation.MuteParticipantUseCase;
import com.a105.zani.session.presentation.request.ModerationRequest;
import com.a105.zani.session.presentation.response.ModerationResponse;

/**
 * 강사 제어 진입점.
 *
 * <p><b>손들기·반응과 달리 REST 다.</b> 제어는 요청한 사람이 결과를 알아야 한다 — 권한이 없는지, 대상이 없는지, 미디어 서버가 죽었는지. STOMP 는 돌려줄 상태 코드가 없어 거절을 별도 큐로
 * 우회해야 하는데, 응답이 곧 결과인 REST 가 이 성격에 맞는다.
 */
@Tag(name = "강사 제어", description = "강사가 학생 마이크를 끈다. 해제는 학생 본인만 할 수 있다")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class ModerationController {

    private final MuteParticipantUseCase muteParticipantUseCase;

    @Operation(summary = "강사 제어", description = """
                    강사가 같은 세션의 **학생** 마이크를 끈다.

                    해제는 제공하지 않는다. 강사가 남의 마이크를 켤 수 있으면 본인 모르게 소리가 나가기 시작한다 —
                    음소거 해제는 학생 본인만 한다(FRD §10.5). 강제 퇴장과 공유 중지도 범위 밖이다(2026-07-30 확정).

                    같은 요청을 다시 보내도 안전하다. 이미 음소거였으면 `alreadyMuted: true` 로 성공을 돌려주고
                    이력을 늘리지 않는다.

                    **미디어 서버를 쓰지 못하면 503 으로 실패한다.** 성공으로 돌려주면 강사 화면에는 음소거로 보이는데
                    학생 소리는 계속 나가는 상태가 되고, 강사는 조용해진 줄 알고 수업을 이어간다.

                    결과를 STOMP 로 따로 알리지 않는다. LiveKit 이 트랙 상태를 모든 참가자에게 전파하므로
                    대상 본인의 마이크 표시도 남의 목록도 이미 맞고, 같은 값을 두 경로로 보내면 늦거나 빠진
                    쪽 때문에 화면이 어긋난다. 이력은 `interaction_events` 에 남아 리포트가 읽는다.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "음소거됨"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "지원하지 않는 동작"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "이 세션의 강사가 아니거나, 대상이 학생이 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "세션 또는 대상 참가자를 찾을 수 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 종료된 세션"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "미디어 서버를 쓸 수 없음")
    })
    @PostMapping("/{sessionId}/moderation")
    public ApiResponse<ModerationResponse> moderate(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "세션 ID") @PathVariable Long sessionId,
            @Valid @RequestBody ModerationRequest request) {
        // 허용 동작은 요청 규격이 검증한다(MUTE 하나). 모르는 값을 조용히 음소거로 처리하면, 나중에 다른
        // 동작이 붙었을 때 옛 클라이언트의 오타가 엉뚱한 제어로 실행된다.
        return ApiResponse.success(ModerationResponse.from(muteParticipantUseCase.mute(new MuteParticipantCommand(
                sessionId, Long.parseLong(jwt.getSubject()), request.targetParticipantId()))));
    }
}
