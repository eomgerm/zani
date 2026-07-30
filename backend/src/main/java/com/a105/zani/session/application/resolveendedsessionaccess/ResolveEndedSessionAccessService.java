package com.a105.zani.session.application.resolveendedsessionaccess;

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

/**
 * 종료된 세션 리포트의 접근 판정.
 *
 * <p>세션 존재 → 멤버십 → 종료 여부 순으로 본다. 진행 중인 세션이라는 사실도 참가자에게만 알린다 — 남의 수업이 지금 열려 있는지를 초대 코드 없이 알 수 있으면 안 된다.
 *
 * <p>진행 중인 세션에 종료 판정 대신 409 를 주는 이유는 실시간 경로가 따로 있기 때문이다. 리포트는 저장된 이력을 재생하므로 수업 중에는 아직 절반만 있는 그림을 보여주게 된다.
 */
@Service
@RequiredArgsConstructor
public class ResolveEndedSessionAccessService implements ResolveEndedSessionAccessUseCase {

    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository participantRepository;

    @Override
    @Transactional(readOnly = true)
    public ResolveEndedSessionAccessResult resolve(ResolveEndedSessionAccessQuery query) {
        Session session = sessionRepository.findById(query.sessionId()).orElseThrow(SessionNotFoundException::new);

        SessionParticipant participant = participantRepository
                .findBySessionIdAndUserId(query.sessionId(), query.memberId())
                .orElseThrow(NotSessionMemberException::new);

        if (!session.isEnded()) {
            throw new SessionNotEndedException();
        }

        // 종료 시각은 도메인 모델이 들고 있지 않아 여기서 채울 값이 없다. 자리를 비워 두는 이유는
        // ResolveEndedSessionAccessResult 의 endedAt 주석에 적었다.
        return new ResolveEndedSessionAccessResult(participant.id(), participant.role(), session.startedAt(), null);
    }
}
