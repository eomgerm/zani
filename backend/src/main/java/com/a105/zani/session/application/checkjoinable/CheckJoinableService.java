package com.a105.zani.session.application.checkjoinable;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.join.SessionJoinPolicy;
import com.a105.zani.session.domain.model.InviteCode;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

/**
 * 초대 코드로 들어갈 수 있는지만 확인한다. <b>아무 것도 쓰지 않는다.</b>
 *
 * <p>행을 잠그지 않는 이유: 이 결과는 "지금 이 순간" 의 답이고, 확인과 실제 입장 사이에 자리는 언제든 줄어들 수 있다. 정원을 지키는 건 입장 경로의 잠금이고 여기서는 안내만 한다. 잠금을 잡으면
 * 코드만 확인하는 요청이 실제 입장을 기다리게 만든다.
 *
 * <p>이미 참가자인 사용자는 정원과 무관하게 통과한다. 새로고침이나 재입장으로 다시 들어오는 경우이며, 자리를 새로 쓰지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CheckJoinableService implements CheckJoinableUseCase {

    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository participantRepository;
    private final SessionJoinPolicy joinPolicy;

    @Override
    @Transactional(readOnly = true)
    public CheckJoinableResult check(CheckJoinableQuery query) {
        String inviteCode = InviteCode.canonicalize(query.inviteCode());
        Session session = sessionRepository.findByInviteCode(inviteCode).orElseThrow(SessionNotFoundException::new);
        joinPolicy.requireJoinable(session);

        int participantCount =
                participantRepository.findBySessionId(session.id()).size();
        boolean alreadyMember = participantRepository
                .findBySessionIdAndUserId(session.id(), query.userId())
                .isPresent();
        if (!alreadyMember) {
            joinPolicy.requireSeatAvailable(participantCount);
        }

        return new CheckJoinableResult(
                session.id(), session.inviteCode(), session.status(), joinPolicy.remainingSeats(participantCount));
    }
}
