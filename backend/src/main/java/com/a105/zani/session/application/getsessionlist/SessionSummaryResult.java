package com.a105.zani.session.application.getsessionlist;

import com.a105.zani.session.domain.model.MemberRole;
import com.a105.zani.session.domain.model.SessionStatus;

public record SessionSummaryResult(
        Long sessionId,
        String inviteCode,
        SessionStatus status,
        MemberRole role) {
}
