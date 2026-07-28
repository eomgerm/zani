package com.a105.zani.session.application.end;

import com.a105.zani.session.domain.model.SessionStatus;

/** 종료 처리 결과. ended는 이 호출이 실제로 상태를 전이시켰는지(이미 종료된 세션이면 false)다. */
public record EndSessionResult(long sessionId, SessionStatus status, boolean ended) {}
