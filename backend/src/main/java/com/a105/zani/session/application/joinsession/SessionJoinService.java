package com.a105.zani.session.application.joinsession;

import java.time.Instant;

import org.springframework.stereotype.Service;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.domain.model.MemberRole;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionMember;
import com.a105.zani.session.domain.repository.SessionMemberRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

@Service
public class SessionJoinService implements JoinSessionUseCase {

    private final SessionRepository sessionRepository;
    private final SessionMemberRepository sessionMemberRepository;

    public SessionJoinService(SessionRepository sessionRepository, SessionMemberRepository sessionMemberRepository) {
        this.sessionRepository = sessionRepository;
        this.sessionMemberRepository = sessionMemberRepository;
    }

    @Override
    public JoinSessionResult join(JoinSessionCommand command) {
        Session session =
                sessionRepository.findByInviteCode(command.inviteCode()).orElseThrow(SessionNotFoundException::new);

        SessionMember member = joinOrRecordAccess(session, command.studentId());

        return new JoinSessionResult(session.id(), session.inviteCode(), session.status(), member.role());
    }

    private SessionMember joinOrRecordAccess(Session session, long studentId) {
        return sessionMemberRepository
                .findBySessionIdAndUserId(session.id(), studentId)
                .map(existing -> {
                    existing.recordAccess(Instant.now());
                    return sessionMemberRepository.save(existing);
                })
                .orElseGet(() -> sessionMemberRepository.save(SessionMember.join(
                        TsidGenerator.generate(), session.id(), studentId, MemberRole.STUDENT, Instant.now())));
    }
}
