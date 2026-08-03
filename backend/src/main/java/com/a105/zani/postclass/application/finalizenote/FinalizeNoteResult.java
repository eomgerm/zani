package com.a105.zani.postclass.application.finalizenote;

import java.time.Instant;

import com.a105.zani.postclass.domain.model.NoteStatus;

/**
 * 확정 결과. 중복 확정도 성공이므로 상태는 언제나 {@code FINALIZED} 다.
 *
 * @param finalizedNow 이번 요청이 확정을 <b>일으켰는지</b>. 수동 완료와 30분 비활성 확정이 겹쳐도 한 요청만 {@code true} 를 받는다. 확정 후속 작업(NOTE-004의 사후
 *     처리 job 생성)은 이 값이 {@code true} 인 경로에서만 해야 세션당 한 번이 지켜진다.
 */
public record FinalizeNoteResult(Long noteId, NoteStatus status, Instant finalizedAt, boolean finalizedNow) {}
