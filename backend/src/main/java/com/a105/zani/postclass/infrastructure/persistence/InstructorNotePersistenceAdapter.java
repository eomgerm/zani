package com.a105.zani.postclass.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.a105.zani.postclass.domain.exception.ConcurrentNoteOpenException;
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

    /**
     * 메모 행을 쓴다. 유니크 제약 위반은 여기서 도메인 오류로 옮긴다 — 강사가 창을 두 개 열어 둔 경우처럼 같은 세션의 메모를 두 요청이 동시에 열려 하면
     * UK_INSTRUCTOR_NOTES_SESSION 이 한쪽을 막는데, 그것은 서버 오류가 아니라 재시도하면 풀리는 충돌이다.
     */
    @Override
    public InstructorNote save(InstructorNote instructorNote) {
        try {
            return mapper.toDomain(instructorNoteJpaRepository.saveAndFlush(mapper.toEntity(instructorNote)));
        } catch (DataIntegrityViolationException exception) {
            throw new ConcurrentNoteOpenException();
        }
    }

    @Override
    public boolean finalizeIfDraft(Long sessionId, Instant finalizedAt) {
        return instructorNoteJpaRepository.finalizeIfDraft(sessionId, finalizedAt) == 1;
    }
}
