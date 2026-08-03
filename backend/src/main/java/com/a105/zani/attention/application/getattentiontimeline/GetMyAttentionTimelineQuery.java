package com.a105.zani.attention.application.getattentiontimeline;

/** 학생이 자기 집중 흐름을 여는 요청. 다른 학생의 것을 가리킬 수 있는 입력이 없다는 점이 중요하다. */
public record GetMyAttentionTimelineQuery(Long sessionId, Long memberId) {}
