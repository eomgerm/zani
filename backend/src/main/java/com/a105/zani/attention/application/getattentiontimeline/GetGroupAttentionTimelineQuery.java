package com.a105.zani.attention.application.getattentiontimeline;

/** 강사가 자기 수업의 익명 집단 타임라인을 여는 요청. memberId 는 인증 주체에서, sessionId 는 경로에서 온다. */
public record GetGroupAttentionTimelineQuery(Long sessionId, Long memberId) {}
