package com.a105.zani.postclass.application.finalizenote;

import com.a105.zani.postclass.domain.exception.ConcurrentNoteOpenException;
import com.a105.zani.session.application.exception.NotSessionInstructorException;

public interface FinalizeNoteUseCase {

    /**
     * 강사 메모를 확정한다(NOTE-003). 확정 후에는 수정·재생성할 수 없다.
     *
     * <p>중복 확정은 오류가 아니라 멱등 성공이다 — 강사가 버튼을 두 번 누르거나 자동 확정과 겹쳤을 때 실패로 돌려주면, 이미 확정된 수업을 두고 클라이언트가 재시도를 반복한다.
     *
     * @throws NotSessionInstructorException 세션 강사가 아님
     * @throws ConcurrentNoteOpenException 다른 요청이 같은 세션의 메모를 먼저 열었음
     */
    FinalizeNoteResult finalizeNote(FinalizeNoteCommand command);
}
