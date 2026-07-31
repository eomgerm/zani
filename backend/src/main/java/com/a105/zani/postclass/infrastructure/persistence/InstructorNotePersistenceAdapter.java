package com.a105.zani.postclass.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.a105.zani.postclass.domain.exception.ConcurrentNoteOpenException;
import com.a105.zani.postclass.domain.exception.NoteAlreadyFinalizedException;
import com.a105.zani.postclass.domain.model.InstructorNote;
import com.a105.zani.postclass.domain.repository.InstructorNoteRepository;
import com.a105.zani.postclass.infrastructure.persistence.mapper.InstructorNotePersistenceMapper;
import com.a105.zani.postclass.infrastructure.persistence.repository.InstructorNoteJpaRepository;

@Component
@RequiredArgsConstructor
public class InstructorNotePersistenceAdapter implements InstructorNoteRepository {

    private final InstructorNoteJpaRepository instructorNoteJpaRepository;
    private final InstructorNotePersistenceMapper mapper;

    @Override
    public Optional<InstructorNote> findBySessionId(Long sessionId) {
        return instructorNoteJpaRepository.findBySessionId(sessionId).map(mapper::toDomain);
    }

    @Override
    public InstructorNote save(InstructorNote instructorNote) {
        if (instructorNote.id() == null) {
            return insert(instructorNote);
        }
        return updateDraft(instructorNote);
    }

    /**
     * 첫 행을 만든다. 유니크 제약 위반은 여기서 도메인 오류로 옮긴다 — 강사가 창을 두 개 열어 둔 경우처럼 같은 세션의 메모를 두 요청이 동시에 열려 하면
     * UK_INSTRUCTOR_NOTES_SESSION 이 한쪽을 막는데, 그것은 서버 오류가 아니라 재시도하면 풀리는 충돌이다.
     */
    private InstructorNote insert(InstructorNote instructorNote) {
        try {
            return mapper.toDomain(instructorNoteJpaRepository.saveAndFlush(mapper.toEntity(instructorNote)));
        } catch (DataIntegrityViolationException exception) {
            throw new ConcurrentNoteOpenException();
        }
    }

    /**
     * 이미 있는 초안에 본문과 마지막 입력 시각만 덮어쓴다.
     *
     * <p>엔티티를 merge 로 저장하지 않는 이유: 그러면 status·finalized_at 까지 읽어 둔 값으로 되돌아간다. 읽은 뒤 다른 경로가 확정했다면 그 확정이 DRAFT 로 사라지고, 이미
     * 만들어진 사후 처리 작업만 남아 편집 가능한 메모를 분석하게 된다. 조건부 UPDATE 가 0 행을 돌려주면 그 사이에 확정된 것이므로 수정 불가로 알린다(409).
     */
    private InstructorNote updateDraft(InstructorNote instructorNote) {
        int updated = instructorNoteJpaRepository.updateDraft(
                instructorNote.sessionId(), instructorNote.content(), instructorNote.lastEditedAt());
        if (updated != 1) {
            throw new NoteAlreadyFinalizedException();
        }
        return instructorNote;
    }

    @Override
    public boolean finalizeIfDraft(Long sessionId, Instant finalizedAt) {
        return instructorNoteJpaRepository.finalizeIfDraft(sessionId, finalizedAt) == 1;
    }

    /** 잠금 읽기로 스냅숏을 우회한다 — 일반 조회는 이 트랜잭션이 처음 읽은 시점을 계속 보므로 방금 커밋된 확정을 놓친다. */
    @Override
    public Optional<Instant> findCommittedFinalizedAt(Long sessionId) {
        return instructorNoteJpaRepository.findFinalizedAtForUpdate(sessionId);
    }
}
