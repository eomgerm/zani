package com.a105.zani.session.application.end;

import com.a105.zani.session.domain.model.SessionEndReason;

public record EndSessionCommand(long sessionId, SessionEndReason reason) {}
