package com.a105.zani.postclass.application.savenotedraft;

import com.a105.zani.postclass.domain.exception.ConcurrentNoteOpenException;
import com.a105.zani.postclass.domain.exception.InvalidNoteContentException;
import com.a105.zani.postclass.domain.exception.NoteAlreadyFinalizedException;
import com.a105.zani.session.application.exception.NotSessionInstructorException;

public interface SaveNoteDraftUseCase {

    /**
     * 강사 메모 초안을 저장하고 30분 비활성 타이머를 초기화한다(NOTE-002).
     *
     * @throws NotSessionInstructorException 세션 강사가 아님
     * @throws InvalidNoteContentException 본문이 5000자를 넘음
     * @throws NoteAlreadyFinalizedException 이미 확정된 메모
     * @throws ConcurrentNoteOpenException 다른 요청이 같은 세션의 메모를 먼저 열었음
     */
    SaveNoteDraftResult save(SaveNoteDraftCommand command);
}
