package com.a105.zani.session.presentation.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param inviteCode 초대 코드. 하이픈·공백·소문자를 허용하고 서버가 정규화한다. 여기서 원문 길이를 8자로 못 박으면 표시형(`GPH7-GQ5Q`)이 형식 오류로 막히므로, 8자 검증은 정규화
 *     이후에 한다. 상한은 사람이 옮겨 적을 수 있는 범위를 넉넉히 잡은 값일 뿐이다.
 */
public record JoinSessionRequest(
        @NotBlank @Size(min = 8, max = 20) String inviteCode) {}
