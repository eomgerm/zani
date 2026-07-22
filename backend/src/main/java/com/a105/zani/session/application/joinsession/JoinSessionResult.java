package com.a105.zani.session.application.joinsession;

import com.a105.zani.session.domain.model.MemberRole;
import com.a105.zani.session.domain.model.SessionStatus;

public record JoinSessionResult(
        Long sessionId,
        String inviteCode,
        SessionStatus status,
        MemberRole role) {
}
