package com.a105.zani.session.application.end;

public record EndSessionCommand(long sessionId, SessionEndReason reason) {}
