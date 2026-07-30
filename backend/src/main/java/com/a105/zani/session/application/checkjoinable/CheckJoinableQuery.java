package com.a105.zani.session.application.checkjoinable;

/** @param userId 확인을 요청한 사용자. 이미 참가자인지 봐야 정원이 찬 수업에 재입장하려는 학생을 잘못 막지 않는다 */
public record CheckJoinableQuery(String inviteCode, long userId) {}
