package com.a105.zani.session.application.end;

/** 강사가 강의실에서 수업을 직접 종료하는 요청. userId는 인증 주체에서, sessionId는 경로에서 온다. */
public record EndSessionByInstructorCommand(long sessionId, long userId) {}
