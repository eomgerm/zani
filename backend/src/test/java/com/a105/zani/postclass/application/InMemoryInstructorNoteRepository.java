package com.a105.zani.postclass.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.a105.zani.postclass.domain.exception.ConcurrentNoteOpenException;
import com.a105.zani.postclass.domain.exception.NoteAlreadyFinalizedException;
import com.a105.zani.postclass.domain.model.InstructorNote;
import com.a105.zani.postclass.domain.model.NoteStatus;
import com.a105.zani.postclass.domain.repository.InstructorNoteRepository;

/**
 * 메모 저장소의 in-memory 대역. 유스케이스 단위 테스트가 공유한다.
 *
 * <p>{@link #finalizeIfDraft}는 실제 어댑터와 같은 계약을 지킨다 — DRAFT 일 때만 전이하고 그 사실을 반환값으로 알린다. 경합 상황은
 * {@link #stealFinalizationBeforeNextTransition}으로 재현한다.
 */
public class InMemoryInstructorNoteRepository implements InstructorNoteRepository {

    private final List<InstructorNote> notes = new ArrayList<>();

    /**
     * 다음 {@link #save} 가 충돌로 실패한다(같은 세션의 메모를 다른 요청이 먼저 열었을 때).
     *
     * <p>실제 어댑터는 유니크 제약 위반(UK_INSTRUCTOR_NOTES_SESSION)을 이 예외로 옮긴다. 대역도 같은 계약을 지킨다.
     */
    public boolean failSaveWithDuplicateKey;

    /** 다음 {@link #finalizeIfDraft} 직전에 다른 경로가 확정을 가져간다(수동·자동 확정 경합). */
    public boolean stealFinalizationBeforeNextTransition;

    /** 확정을 가져간 쪽이 기록하는 시각. 진 경로가 이 값을 응답하는지 보기 위해 호출 시각과 다르게 둘 수 있다. */
    public Instant stolenFinalizedAt;

    /**
     * 다음 {@link #save} 직전에 다른 경로가 확정을 가져간다.
     *
     * <p>초안을 읽은 뒤 저장하기 전에 30분 비활성 확정이 끼어드는 상황이다. 실제 어댑터는 조건부 UPDATE 라 그 행을 건드리지 못한다.
     */
    public boolean stealFinalizationBeforeNextSave;

    private long nextId = 1L;

    @Override
    public Optional<InstructorNote> findBySessionId(Long sessionId) {
        return notes.stream().filter(note -> note.sessionId().equals(sessionId)).findFirst();
    }

    @Override
    public InstructorNote save(InstructorNote instructorNote) {
        if (failSaveWithDuplicateKey) {
            throw new ConcurrentNoteOpenException();
        }
        if (stealFinalizationBeforeNextSave) {
            stealFinalizationBeforeNextSave = false;
            replace(instructorNote.sessionId(), Instant.EPOCH);
        }
        Optional<InstructorNote> existing = findBySessionId(instructorNote.sessionId());
        if (instructorNote.id() == null) {
            if (existing.isPresent()) {
                throw new ConcurrentNoteOpenException();
            }
            InstructorNote stored = copyWithId(instructorNote, nextId++);
            notes.add(stored);
            return stored;
        }
        // 조건부 UPDATE 와 같은 계약: 확정된 행에는 본문을 덮어쓸 수 없다.
        if (existing.isPresent() && existing.get().isFinalized()) {
            throw new NoteAlreadyFinalizedException();
        }
        notes.removeIf(note -> note.id().equals(instructorNote.id()));
        notes.add(instructorNote);
        return instructorNote;
    }

    /** 실제 어댑터는 잠금 읽기로 커밋본을 읽는다. 대역은 스냅숏이 없으므로 저장된 값을 그대로 돌려준다. */
    @Override
    public Optional<Instant> findCommittedFinalizedAt(Long sessionId) {
        return findBySessionId(sessionId).map(InstructorNote::finalizedAt);
    }

    @Override
    public boolean finalizeIfDraft(Long sessionId, Instant finalizedAt) {
        if (stealFinalizationBeforeNextTransition) {
            stealFinalizationBeforeNextTransition = false;
            replace(sessionId, stolenFinalizedAt != null ? stolenFinalizedAt : finalizedAt);
            return false;
        }
        InstructorNote note = findBySessionId(sessionId).orElse(null);
        if (note == null || note.isFinalized()) {
            return false;
        }
        replace(sessionId, finalizedAt);
        return true;
    }

    private void replace(Long sessionId, Instant finalizedAt) {
        InstructorNote note = findBySessionId(sessionId).orElse(null);
        if (note == null) {
            return;
        }
        notes.remove(note);
        notes.add(InstructorNote.reconstitute(
                note.id(),
                note.sessionId(),
                note.instructorParticipantId(),
                NoteStatus.FINALIZED,
                note.content(),
                note.lastEditedAt(),
                finalizedAt));
    }

    private InstructorNote copyWithId(InstructorNote note, long id) {
        return InstructorNote.reconstitute(
                id,
                note.sessionId(),
                note.instructorParticipantId(),
                note.status(),
                note.content(),
                note.lastEditedAt(),
                note.finalizedAt());
    }
}
