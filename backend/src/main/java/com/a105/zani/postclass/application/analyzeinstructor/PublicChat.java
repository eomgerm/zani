package com.a105.zani.postclass.application.analyzeinstructor;

/** 공개 채팅 한 줄. 발신자를 담지 않는다 — 강사 리포트는 학생별 발화를 노출하지 않는다(REPORT-I-002). */
public record PublicChat(long occurredOffsetMs, String content) {}
