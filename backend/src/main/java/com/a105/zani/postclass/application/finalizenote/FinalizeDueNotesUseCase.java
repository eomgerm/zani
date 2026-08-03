package com.a105.zani.postclass.application.finalizenote;

public interface FinalizeDueNotesUseCase {

    /** 마지막 입력이 30분을 넘긴 초안을 자동 확정한다(FRD §16 NOTE-003). 확정한 건수를 반환한다. */
    int finalizeDueNotes();
}
