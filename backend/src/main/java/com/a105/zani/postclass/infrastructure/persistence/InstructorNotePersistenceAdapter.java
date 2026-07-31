package com.a105.zani.postclass.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.a105.zani.common.persistence.TsidGenerator;
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
     * 첫 행을 만든다. 유니크 제약 위반<b>만</b> 도메인 오류로 옮긴다 — 강사가 창을 두 개 열어 둔 경우처럼 같은 세션의 메모를 두 요청이 동시에 열려 하면
     * UK_INSTRUCTOR_NOTES_SESSION 이 한쪽을 막는데, 그것은 서버 오류가 아니라 재시도하면 풀리는 충돌이다.
     *
     * <p>다른 제약 위반은 그대로 올린다. 이 테이블은 session_participants 로 FK 가 걸려 있어, 잡는 범위를 넓히면 없는 참가자로 들어온 요청이 "다른 요청이 먼저 열었다"(409)로
     * 위장된다 — 재시도해도 절대 풀리지 않는데 클라이언트는 재시도할 수 있는 충돌로 읽는다. FK 위반은 서버 쪽 결함이므로 500 이 맞다.
     */
    private InstructorNote insert(InstructorNote instructorNote) {
        try {
            return mapper.toDomain(instructorNoteJpaRepository.saveAndFlush(mapper.toEntity(instructorNote)));
        } catch (DataIntegrityViolationException exception) {
            if (!isUniqueViolation(exception)) {
                throw exception;
            }
            throw new ConcurrentNoteOpenException();
        }
    }

    /** 제약 이름을 문자열로 맞추지 않는 이유: Hibernate 가 위반 종류를 이미 분류해 준다(MySQL 은 유니크 이름에 테이블 접두어를 붙인다). */
    private static boolean isUniqueViolation(DataIntegrityViolationException exception) {
        return exception.getCause() instanceof ConstraintViolationException violation
                && violation.getKind() == ConstraintKind.UNIQUE;
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

    @Override
    public Optional<Long> insertFinalizedIfAbsent(Long sessionId, Long instructorParticipantId, Instant finalizedAt) {
        long id = TsidGenerator.generate();
        int inserted = instructorNoteJpaRepository.insertFinalizedIfAbsent(
                id, sessionId, instructorParticipantId, finalizedAt);
        return inserted == 1 ? Optional.of(id) : Optional.empty();
    }

    /** 잠금 읽기로 스냅숏을 우회한다 — 일반 조회는 이 트랜잭션이 처음 읽은 시점을 계속 보므로 방금 커밋된 행을 놓친다. */
    @Override
    public Optional<Long> findCommittedNoteId(Long sessionId) {
        return instructorNoteJpaRepository.findIdForUpdate(sessionId);
    }

    /** 잠금 읽기로 스냅숏을 우회한다 — 일반 조회는 이 트랜잭션이 처음 읽은 시점을 계속 보므로 방금 커밋된 확정을 놓친다. */
    @Override
    public Optional<Instant> findCommittedFinalizedAt(Long sessionId) {
        return instructorNoteJpaRepository.findFinalizedAtForUpdate(sessionId);
    }
}
