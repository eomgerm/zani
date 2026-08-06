package com.a105.zani.member.presentation.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.a105.zani.common.response.ApiResponse;
import com.a105.zani.member.application.getcurrent.GetCurrentMemberQuery;
import com.a105.zani.member.application.getcurrent.GetCurrentMemberResult;
import com.a105.zani.member.application.getcurrent.GetCurrentMemberUseCase;
import com.a105.zani.member.application.updatedisplayname.UpdateDisplayNameCommand;
import com.a105.zani.member.application.updatedisplayname.UpdateDisplayNameUseCase;
import com.a105.zani.member.application.updatereportemail.UpdateReportEmailCommand;
import com.a105.zani.member.application.updatereportemail.UpdateReportEmailUseCase;
import com.a105.zani.member.presentation.request.UpdateDisplayNameRequest;
import com.a105.zani.member.presentation.request.UpdateReportEmailRequest;
import com.a105.zani.member.presentation.response.GetCurrentMemberResponse;
import com.a105.zani.member.presentation.response.UpdateDisplayNameResponse;
import com.a105.zani.member.presentation.response.UpdateReportEmailResponse;

@Tag(name = "회원", description = "로그인한 본인 정보 조회.")
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

    private final GetCurrentMemberUseCase getCurrentMemberUseCase;
    private final UpdateReportEmailUseCase updateReportEmailUseCase;
    private final UpdateDisplayNameUseCase updateDisplayNameUseCase;

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

    @Operation(summary = "강의 리포트 알림 설정 변경", description = """
                    로그인한 본인의 강의 리포트 완료 이메일 수신 여부를 켜거나 끕니다. OFF 로 두면 리포트가 완료돼도 이메일을 받지 않습니다.
                    본인 설정만 바꿀 수 있습니다.
                    """)
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "변경 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "로그인이 필요합니다. (`COMM_401`)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "토큰의 회원이 더 이상 존재하지 않습니다. (`MEMBER_APP_002`)")
    })
    @PatchMapping("/me")
    public ApiResponse<UpdateReportEmailResponse> updateReportEmail(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateReportEmailRequest request) {
        return ApiResponse.success(UpdateReportEmailResponse.from(updateReportEmailUseCase.updateReportEmail(
                new UpdateReportEmailCommand(Long.parseLong(jwt.getSubject()), request.reportEmailEnabled()))));
    }

    @Operation(summary = "표시 이름 변경", description = """
                    로그인한 본인의 표시 이름을 바꿉니다. 앞뒤 공백은 다듬어 저장하며, 본인 이름만 바꿀 수 있습니다.
                    이메일은 Google 계정에서 오는 값이라 바꿀 수 없습니다.
                    """)
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "변경 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "이름이 비어 있거나 100자를 넘습니다. (`COMM_400_001`, `MEMBER_002`)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "로그인이 필요합니다. (`COMM_401`)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "토큰의 회원이 더 이상 존재하지 않습니다. (`MEMBER_APP_002`)")
    })
    @PatchMapping("/me/display-name")
    public ApiResponse<UpdateDisplayNameResponse> updateDisplayName(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateDisplayNameRequest request) {
        return ApiResponse.success(UpdateDisplayNameResponse.from(updateDisplayNameUseCase.updateDisplayName(
                new UpdateDisplayNameCommand(Long.parseLong(jwt.getSubject()), request.displayName()))));
    }
}
