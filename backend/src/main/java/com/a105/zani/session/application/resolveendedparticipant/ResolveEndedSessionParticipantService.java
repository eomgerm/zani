package com.a105.zani.session.application.resolveendedparticipant;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.SessionNotEndedException;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

/** 종료 세션 참가자 확인. 멤버십 → 세션 존재 → 종료 여부 순서는 진행 중 세션 쪽과 같다(비멤버에게 세션 존재·상태를 알리지 않는다). */
@Service
@RequiredArgsConstructor
public class ResolveEndedSessionParticipantService implements ResolveEndedSessionParticipantUseCase {

    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository participantRepository;

    @Override
    @Transactional(readOnly = true)
    public ResolveEndedSessionParticipantResult resolve(ResolveEndedSessionParticipantQuery query) {
        SessionParticipant participant = participantRepository
                .findBySessionIdAndUserId(query.sessionId(), query.userId())
                .orElseThrow(NotSessionMemberException::new);

        Session session = sessionRepository.findById(query.sessionId()).orElseThrow(SessionNotFoundException::new);
        if (!session.isEnded()) {
            throw new SessionNotEndedException();
        }

        return new ResolveEndedSessionParticipantResult(
                participant.id(), participant.role(), session.startedAt(), session.endedAt());
    }
}
