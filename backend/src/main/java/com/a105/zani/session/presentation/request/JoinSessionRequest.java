package com.a105.zani.session.presentation.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 초대 코드 입장 요청.
 *
 * <p>표시형({@code A7KM-2PQR})과 저장형({@code A7KM2PQR})을 모두 받고 소문자 입력도 허용한다. 하이픈 제거·대문자 정규화는 도메인의 {@code InviteCode}가 담당하므로,
 * 여기서는 정규화 전에 명백히 어긋나는 입력만 걸러낸다.
 */
public record JoinSessionRequest(
        @Schema(description = "8자리 초대 코드. 하이픈과 소문자를 허용한다.", example = "A7KM-2PQR")
        @NotBlank @Pattern(regexp = "[A-Za-z0-9]{4}-?[A-Za-z0-9]{4}", message = "초대 코드는 하이픈을 제외하고 영문·숫자 8자여야 합니다.") String inviteCode) {}
