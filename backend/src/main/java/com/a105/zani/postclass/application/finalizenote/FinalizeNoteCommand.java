package com.a105.zani.postclass.application.finalizenote;

/** 강사의 `작성 완료`. 초안을 한 번도 저장하지 않은 세션에도 쓸 수 있다(메모 없이 완료). */
public record FinalizeNoteCommand(Long sessionId, Long userId) {}
