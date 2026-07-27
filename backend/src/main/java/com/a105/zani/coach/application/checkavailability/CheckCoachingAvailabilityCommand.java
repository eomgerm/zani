package com.a105.zani.coach.application.checkavailability;

/** 코칭 가용 여부 확인 요청. 수업 시작(방 생성) 시점에 sessionId 로 트리거된다. */
public record CheckCoachingAvailabilityCommand(long sessionId) {}
