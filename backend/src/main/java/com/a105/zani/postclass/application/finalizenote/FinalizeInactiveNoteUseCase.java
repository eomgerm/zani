package com.a105.zani.postclass.application.finalizenote;

public interface FinalizeInactiveNoteUseCase {

    /**
     * 30분 동안 입력이 없던 초안을 확정한다(FRD §16 NOTE-003). 확정이 이 호출로 일어났으면 {@code true}.
     *
     * <p>강사 요청 경로({@link FinalizeNoteUseCase})와 달리 인증 주체가 없다 — 호출자는 스윕({@link FinalizeDueNotesUseCase})뿐이고, 대상은 이미 "마지막
     * 입력이 30분 전"이라는 조건으로 골라져 있다.
     *
     * <p>한 건이 한 트랜잭션이다. 확정에 딸린 후속 기록(사후 처리 job)이 확정과 함께 커밋되어야 하므로, 스윕이 아니라 이 경계가 트랜잭션을 잡는다.
     */
    boolean finalizeInactiveNote(Long sessionId);
}
