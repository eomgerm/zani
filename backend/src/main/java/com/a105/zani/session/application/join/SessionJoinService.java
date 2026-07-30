package com.a105.zani.session.application.join;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.session.application.exception.SessionEndedException;
import com.a105.zani.session.application.exception.SessionFullException;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.exception.SessionNotStartedException;
import com.a105.zani.session.domain.model.InviteCode;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

/**
 * 초대 코드로 수업에 들어온다. 같은 코드로 여러 번 호출해도 참가자를 새로 만들지 않는다(새로고침·재입장 대비).
 *
 * <p>세션 행을 잠근 채로 정원을 세는 게 핵심이다. 잠금 없이 세면 29명일 때 동시에 들어온 두 요청이 둘 다 자리가 있다고 읽어 정원을 넘긴다. 잠금은 같은 수업의 입장만 직렬화하므로 다른 수업의 입장은
 * 서로 기다리지 않는다.
 */
@Service
public class SessionJoinService implements JoinSessionUseCase {

    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final Clock clock;

    public SessionJoinService(
            SessionRepository sessionRepository,
            SessionParticipantRepository sessionParticipantRepository,
            Clock clock) {
        this.sessionRepository = sessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public JoinSessionResult join(JoinSessionCommand command) {
        // 사람이 옮겨 적은 값이라 형태가 일정하지 않다. 저장 형태로 맞춘 뒤에 조회한다.
        String inviteCode = InviteCode.canonicalize(command.inviteCode());
        Session session =
                sessionRepository.findByInviteCodeForUpdate(inviteCode).orElseThrow(SessionNotFoundException::new);
        requireJoinable(session);

        SessionParticipant participant = joinOrRecordAccess(session, command.studentId());

        return new JoinSessionResult(session.id(), session.inviteCode(), session.status(), participant.role());
    }

    /** 입장은 진행 중인 수업에만 열린다. 시작 전과 종료 후를 다른 코드로 구분해, 학생이 "기다려야 하는지 끝난 건지"를 알 수 있게 한다. */
    private void requireJoinable(Session session) {
        if (session.isClosed()) {
            throw new SessionEndedException();
        }
        if (!session.isLive()) {
            throw new SessionNotStartedException();
        }
    }

    private SessionParticipant joinOrRecordAccess(Session session, long studentId) {
        return sessionParticipantRepository
                .findBySessionIdAndUserId(session.id(), studentId)
                .map(existing -> {
                    existing.recordAccess(clock.instant());
                    return sessionParticipantRepository.save(existing);
                })
                .orElseGet(() -> {
                    // 이미 들어와 있는 참가자의 재입장은 자리를 새로 쓰지 않으므로, 새 참가자일 때만 정원을 센다.
                    // 강사도 참가자 행을 갖고 있어 이 수에 포함된다 — 정원 30명은 강사를 포함한 값이다.
                    // 명단을 읽어 세는 건 정원이 30이라 부담이 없고, 포트에 카운트 전용 메서드를 늘리지 않는다.
                    if (sessionParticipantRepository
                                    .findBySessionId(session.id())
                                    .size()
                            >= Session.CAPACITY) {
                        throw new SessionFullException();
                    }
                    return sessionParticipantRepository.save(SessionParticipant.join(
                            TsidGenerator.generate(),
                            session.id(),
                            studentId,
                            SessionParticipantRole.STUDENT,
                            clock.instant()));
                });
    }
}
