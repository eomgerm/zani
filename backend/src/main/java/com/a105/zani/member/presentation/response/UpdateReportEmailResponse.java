package com.a105.zani.member.presentation.response;

import com.a105.zani.member.application.updatereportemail.UpdateReportEmailResult;

public record UpdateReportEmailResponse(boolean reportEmailEnabled) {

    public static UpdateReportEmailResponse from(UpdateReportEmailResult result) {
        return new UpdateReportEmailResponse(result.reportEmailEnabled());
    }
}
