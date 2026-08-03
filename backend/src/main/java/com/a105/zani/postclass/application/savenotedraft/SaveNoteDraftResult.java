package com.a105.zani.postclass.application.savenotedraft;

import java.time.Instant;

import com.a105.zani.postclass.domain.model.InstructorNote;
import com.a105.zani.postclass.domain.model.NoteStatus;

/**
 * 초안 저장 결과.
 *
 * @param lastEditedAt 이번 입력이 기록된 시각. 30분 비활성 타이머가 여기서 다시 시작한다(NOTE-002).
 */
public record SaveNoteDraftResult(Long noteId, NoteStatus status, Instant lastEditedAt) {

    public static SaveNoteDraftResult from(InstructorNote note) {
        return new SaveNoteDraftResult(note.id(), note.status(), note.lastEditedAt());
    }
}
