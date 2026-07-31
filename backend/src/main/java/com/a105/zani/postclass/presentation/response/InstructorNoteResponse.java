package com.a105.zani.postclass.presentation.response;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.postclass.application.finalizenote.FinalizeNoteResult;
import com.a105.zani.postclass.application.savenotedraft.SaveNoteDraftResult;
import com.a105.zani.postclass.domain.model.NoteStatus;

/** 강사 사후 메모의 현재 상태. 초안 저장과 확정이 같은 모양을 돌려준다. */
@Schema(description = "강사 사후 메모 상태")
public record InstructorNoteResponse(
        @Schema(description = "메모 ID", example = "742891573920571392")
        Long noteId,

        @Schema(description = "메모 상태. FINALIZED 이후에는 수정할 수 없다.", example = "DRAFT")
        NoteStatus status,

        @Schema(description = "이 상태가 기록된 시각(UTC). 초안이면 마지막 입력 시각, 확정이면 확정 시각이다.", example = "2026-07-30T09:12:00Z")
        Instant updatedAt) {

    public static InstructorNoteResponse from(SaveNoteDraftResult result) {
        return new InstructorNoteResponse(result.noteId(), result.status(), result.lastEditedAt());
    }

    public static InstructorNoteResponse from(FinalizeNoteResult result) {
        return new InstructorNoteResponse(result.noteId(), result.status(), result.finalizedAt());
    }
}
