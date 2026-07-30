package com.a105.zani.postclass.application.finalizenote;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.postclass.domain.model.InstructorNote;
import com.a105.zani.postclass.domain.model.NoteStatus;
import com.a105.zani.postclass.domain.repository.InstructorNoteRepository;
import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantQuery;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantResult;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * 강사 메모를 확정한다(FRD §16 NOTE-003).
 *
 * <p>확정 경로는 둘이다: 여기(수동 완료)와 30분 비활성 자동 확정(티켓 90). 둘이 동시에 들어와도 확정은 한 번만 일어나야 하므로, 상태를 읽고 쓰는 대신 저장소의 조건부 전환 한 문장에 맡긴다. 이
 * 서비스는 그 전환의 승자에게만 {@code finalizedNow=true} 를 준다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinalizeNoteService implements FinalizeNoteUseCase {

    private final ResolveEndedSessionParticipantUseCase resolveEndedSessionParticipantUseCase;
    private final InstructorNoteRepository instructorNoteRepository;
    private final Clock clock;

    @Override
    @Transactional
    public FinalizeNoteResult finalizeNote(FinalizeNoteCommand command) {
        ResolveEndedSessionParticipantResult participant = resolveEndedSessionParticipantUseCase.resolve(
                new ResolveEndedSessionParticipantQuery(command.sessionId(), command.userId()));
        if (participant.role() != SessionParticipantRole.INSTRUCTOR) {
            throw new NotSessionInstructorException();
        }

        Instant now = clock.instant();
        Optional<InstructorNote> existing = instructorNoteRepository.findBySessionId(command.sessionId());
        if (existing.isEmpty()) {
            return finalizeWithoutDraft(command.sessionId(), participant.participantId(), now);
        }

        InstructorNote note = existing.get();
        if (note.isFinalized()) {
            return new FinalizeNoteResult(note.id(), note.status(), note.finalizedAt(), false);
        }
        if (!instructorNoteRepository.finalizeIfDraft(command.sessionId(), now)) {
            // 자동 확정이 먼저 이겼다. 확정은 이미 한 번 일어났으므로 성공으로 돌려주되 후속 작업은 이 경로에서 하지 않는다.
            log.debug("메모가 이미 다른 경로에서 확정됐습니다. sessionId={}, noteId={}", command.sessionId(), note.id());
            Instant finalizedAt = instructorNoteRepository
                    .findBySessionId(command.sessionId())
                    .map(InstructorNote::finalizedAt)
                    .orElse(now);
            return new FinalizeNoteResult(note.id(), NoteStatus.FINALIZED, finalizedAt, false);
        }
        return new FinalizeNoteResult(note.id(), NoteStatus.FINALIZED, now, true);
    }

    /**
     * 메모 없이 완료(FRD §16). 확정 행이 없으면 사후 파이프라인이 시작될 근거가 없으므로 빈 확정 행을 남긴다.
     *
     * <p>자동 저장과 확정이 같은 순간에 첫 행을 만들려 하면 저장소가 ConcurrentNoteOpenException 을 던진다(409). 재시도하면 이미 만들어진 행을 보고 멱등하게 확정된다.
     */
    private FinalizeNoteResult finalizeWithoutDraft(Long sessionId, Long participantId, Instant now) {
        InstructorNote created =
                instructorNoteRepository.save(InstructorNote.finalizedWithoutDraft(sessionId, participantId, now));
        return new FinalizeNoteResult(created.id(), created.status(), created.finalizedAt(), true);
    }
}
