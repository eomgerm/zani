package com.a105.zani.report.application.getsessionsummary;

/**
 * 한 세션의 수업 요약을 달라는 요청.
 *
 * <p>{@code memberId} 는 인증 주체에서만 온다. 요청 본문이나 경로로 받으면 남의 자격으로 요약을 읽어 갈 수 있다.
 */
public record GetSessionSummaryQuery(Long sessionId, Long memberId) {}
