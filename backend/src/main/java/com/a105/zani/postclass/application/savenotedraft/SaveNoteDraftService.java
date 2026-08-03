package com.a105.zani.postclass.application.savenotedraft;

import java.time.Clock;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.postclass.domain.model.InstructorNote;
import com.a105.zani.postclass.domain.repository.InstructorNoteRepository;
import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantQuery;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantResult;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/** 종료된 수업의 강사 메모 초안을 저장한다(FRD §16). 저장될 때마다 마지막 입력 시각이 갱신되어 30분 비활성 타이머가 초기화된다. */
@Service
@RequiredArgsConstructor
public class SaveNoteDraftService implements SaveNoteDraftUseCase {

    private final ResolveEndedSessionParticipantUseCase resolveEndedSessionParticipantUseCase;
    private final InstructorNoteRepository instructorNoteRepository;
    private final Clock clock;

    @Override
    @Transactional
    public SaveNoteDraftResult save(SaveNoteDraftCommand command) {
        ResolveEndedSessionParticipantResult participant = resolveEndedSessionParticipantUseCase.resolve(
                new ResolveEndedSessionParticipantQuery(command.sessionId(), command.userId()));
        // 메모는 수업을 진행한 강사만 남긴다. 학생에게는 자기 리포트만 열린다(FRD §21 권한표).
        if (participant.role() != SessionParticipantRole.INSTRUCTOR) {
            throw new NotSessionInstructorException();
        }

        InstructorNote note = instructorNoteRepository
                .findBySessionId(command.sessionId())
                .orElseGet(() -> InstructorNote.open(command.sessionId(), participant.participantId()));
        note.saveDraft(command.content(), clock.instant());

        // 다른 요청이 이 세션의 메모를 먼저 열었으면 저장소가 ConcurrentNoteOpenException 을 던진다(409).
        // 이 트랜잭션은 그 시점에 이미 롤백 대상이라 여기서 다시 쓸 수 없고, 다음 자동 저장이 만들어진 행에 얹힌다.
        return SaveNoteDraftResult.from(instructorNoteRepository.save(note));
    }
}
