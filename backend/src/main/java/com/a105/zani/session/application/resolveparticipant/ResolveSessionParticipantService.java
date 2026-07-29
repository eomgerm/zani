package com.a105.zani.session.application.resolveparticipant;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.SessionAlreadyEndedException;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

/** 세션 참가자 확인. 멤버십 → 세션 존재 → 종료 여부 순서는 presence와 같다(비멤버에게 세션 존재·상태를 알리지 않는다). */
@Service
@RequiredArgsConstructor
public class ResolveSessionParticipantService implements ResolveSessionParticipantUseCase {

    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository participantRepository;

    @Override
    @Transactional(readOnly = true)
    public ResolveSessionParticipantResult resolve(ResolveSessionParticipantQuery query) {
        SessionParticipant participant = participantRepository
                .findBySessionIdAndUserId(query.sessionId(), query.userId())
                .orElseThrow(NotSessionMemberException::new);

        Session session = sessionRepository.findById(query.sessionId()).orElseThrow(SessionNotFoundException::new);
        if (session.hasStartedEnding()) {
            throw new SessionAlreadyEndedException();
        }

        // 아직 시작하지 않은 세션은 시작·만료 시각이 없다. 강사가 준비 화면에서 확인할 때가 그 경우다.
        return new ResolveSessionParticipantResult(
                participant.id(),
                participant.role(),
                session.startedAt(),
                session.expiresAt().orElse(null));
    }
}
