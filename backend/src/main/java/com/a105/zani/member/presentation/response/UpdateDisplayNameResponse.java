package com.a105.zani.member.presentation.response;

import com.a105.zani.member.application.updatedisplayname.UpdateDisplayNameResult;

public record UpdateDisplayNameResponse(String displayName) {

    public static UpdateDisplayNameResponse from(UpdateDisplayNameResult result) {
        return new UpdateDisplayNameResponse(result.displayName());
    }
}
