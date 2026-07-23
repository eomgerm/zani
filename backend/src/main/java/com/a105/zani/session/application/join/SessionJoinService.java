package com.a105.zani.session.application.join;

import java.time.Instant;

import org.springframework.stereotype.Service;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

@Service
public class SessionJoinService implements JoinSessionUseCase {

    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;

    public SessionJoinService(
            SessionRepository sessionRepository, SessionParticipantRepository sessionParticipantRepository) {
        this.sessionRepository = sessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
    }

    @Override
    public JoinSessionResult join(JoinSessionCommand command) {
        Session session =
                sessionRepository.findByInviteCode(command.inviteCode()).orElseThrow(SessionNotFoundException::new);

        SessionParticipant participant = joinOrRecordAccess(session, command.studentId());

        return new JoinSessionResult(session.id(), session.inviteCode(), session.status(), participant.role());
    }

    private SessionParticipant joinOrRecordAccess(Session session, long studentId) {
        return sessionParticipantRepository
                .findBySessionIdAndUserId(session.id(), studentId)
                .map(existing -> {
                    existing.recordAccess(Instant.now());
                    return sessionParticipantRepository.save(existing);
                })
                .orElseGet(() -> sessionParticipantRepository.save(SessionParticipant.join(
                        TsidGenerator.generate(),
                        session.id(),
                        studentId,
                        SessionParticipantRole.STUDENT,
                        Instant.now())));
    }
}
