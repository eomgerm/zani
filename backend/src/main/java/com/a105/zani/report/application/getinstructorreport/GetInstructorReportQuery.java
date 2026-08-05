package com.a105.zani.report.application.getinstructorreport;

/** 강사가 자기 수업의 리포트를 여는 요청. memberId 는 인증 주체에서, sessionId 는 경로에서 온다. */
public record GetInstructorReportQuery(Long sessionId, Long memberId) {}
