package com.a105.zani.attention.presentation.controller;

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

import com.a105.zani.attention.application.polltip.PollCoachingTipCommand;
import com.a105.zani.attention.application.polltip.PollCoachingTipUseCase;
import com.a105.zani.attention.presentation.response.CoachingTipResponse;
import com.a105.zani.common.response.ApiResponse;

@Tag(name = "수업 코칭 팁", description = "강사가 대기 중인 익명 코칭 팁을 가져간다")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class CoachingTipController {

    private final PollCoachingTipUseCase pollCoachingTipUseCase;

    @Operation(summary = "강사 코칭 팁 조회(폴링)", description = """
                    강사 클라이언트가 10초마다 호출한다. 이 호출이 코칭 트리거 판정을 겸한다 — 최근 5분 유의 학생 비율이
                    30% 이상이고 강사 오디오가 60초 이상 확보돼 있으면 트리거를 열고, 전사와 팁 생성은 기다리지 않고 바로 응답한다.
                    완성된 팁은 다음 폴링에 실려 나온다.

                    표시할 팁이 없어도 200 으로 응답한다. 204 로 하면 본문이 없어 미표시 사유를 함께 전달할 수 없다.

                    triggerId 는 같은 트리거인 동안 계속 같은 값이다. 프론트는 이미 띄운 triggerId 를 기억해 카드를
                    두 번 띄우지 않는다. 트리거 하나당 쿨타임 10분이며, 쿨타임이 끝나면 세 필드가 모두 null 로 돌아간다.

                    tip 이 null 이고 unavailableReason 도 null 이면 팁을 만들고 있는 중이다. 강사 화면의 코칭 비활성
                    표시는 이 응답의 사유가 아니라 폴링 자체의 연속 실패로 판단한다(티켓 76).

                    학생 이름·개별 응답·개별 판정은 어떤 경우에도 담기지 않는다. 비율은 익명 집계다.

                    오디오는 LiveKit egress 가 서버로 직접 스트리밍한다. 브라우저에 업로드를 요청하지 않으므로
                    이 응답에 업로드 요청 항목은 없다(티켓 198).""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "대기 중인 팁, 생성 중 상태, 미표시 사유, 또는 아무것도 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "해당 세션의 멤버가 아니거나, 팁을 받을 수 없는 역할(학생)임"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "세션을 찾을 수 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "이미 종료된 세션. 클라이언트는 폴링을 멈춘다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503",
                description = "코칭 상태 저장소(Redis) 사용 불가. 코칭만 멈추고 수업은 계속한다.")
    })
    @GetMapping("/{sessionId}/coaching-tip")
    public ApiResponse<CoachingTipResponse> poll(
            @AuthenticationPrincipal Jwt jwt, @Parameter(description = "세션 ID") @PathVariable Long sessionId) {
        return ApiResponse.success(CoachingTipResponse.from(
                pollCoachingTipUseCase.poll(new PollCoachingTipCommand(sessionId, Long.parseLong(jwt.getSubject())))));
    }
}
