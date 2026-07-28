package com.a105.zani.member.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.a105.zani.common.response.ApiResponse;
import com.a105.zani.member.application.getcurrent.GetCurrentMemberQuery;
import com.a105.zani.member.application.getcurrent.GetCurrentMemberResult;
import com.a105.zani.member.application.getcurrent.GetCurrentMemberUseCase;
import com.a105.zani.member.presentation.response.GetCurrentMemberResponse;

@Tag(name = "회원", description = "로그인한 본인 정보 조회.")
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

    private final GetCurrentMemberUseCase getCurrentMemberUseCase;

    @Operation(summary = "내 정보 조회", description = """
                    Access Token 이 가리키는 로그인한 본인의 정보를 돌려줍니다. 새로고침 직후 세션을 복원할 때 사용합니다.
                    """)
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "로그인이 필요합니다. (`COMM_401`)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "토큰의 회원이 더 이상 존재하지 않습니다. (`MEMBER_APP_002`)")
    })
    @GetMapping("/me")
    public ApiResponse<GetCurrentMemberResponse> me(@AuthenticationPrincipal Jwt jwt) {
        GetCurrentMemberResult result =
                getCurrentMemberUseCase.getCurrentMember(new GetCurrentMemberQuery(Long.parseLong(jwt.getSubject())));
        return ApiResponse.success(GetCurrentMemberResponse.from(result));
    }
}
