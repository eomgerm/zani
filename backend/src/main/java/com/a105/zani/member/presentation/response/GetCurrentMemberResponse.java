package com.a105.zani.member.presentation.response;

import com.a105.zani.member.application.getcurrent.GetCurrentMemberResult;

public record GetCurrentMemberResponse(
        String email, String displayName, String profileImageUrl, boolean reportEmailEnabled) {

    public static GetCurrentMemberResponse from(GetCurrentMemberResult result) {
        return new GetCurrentMemberResponse(
                result.email(), result.displayName(), result.profileImageUrl(), result.reportEmailEnabled());
    }
}
