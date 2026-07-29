package com.a105.zani.session.application.create;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionStatusChange;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;
import com.a105.zani.session.domain.repository.SessionStatusChangeRepository;

/**
 * 세션과 강사 참가 관계를 한 트랜잭션으로 저장한다. 강사도 참가 관계를 가져야 생성 직후 미디어 토큰을 받고 presence heartbeat를 보낼 수 있다(가이드 §5). 둘 중 하나만 남으면 강사가 자기
 * 수업에 들어가지 못하므로 원자적으로 저장해야 한다.
 *
 * <p>매 시도를 독립된 트랜잭션으로 수행하는 이유: 초대 코드 유니크 충돌은 flush 시점에 드러나며, 실패한 트랜잭션의 영속성 컨텍스트는 재사용할 수 없다. 저장을
 * {@link Propagation#REQUIRES_NEW} 경계로 분리해, 한 시도가 롤백되어도 다음 재시도가 오염되지 않은 트랜잭션에서 실행되도록 보장한다.
 */
@Component
class NewSessionSaver {

    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final SessionStatusChangeRepository statusChangeRepository;

    NewSessionSaver(
            SessionRepository sessionRepository,
            SessionParticipantRepository sessionParticipantRepository,
            SessionStatusChangeRepository statusChangeRepository) {
        this.sessionRepository = sessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
        this.statusChangeRepository = statusChangeRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Session save(Session session, SessionParticipant instructor, Instant createdAt) {
        // 참가 관계와 이력이 session_id를 FK로 참조하므로 세션을 먼저 flush 해야 한다(SessionRepository.save가 saveAndFlush).
        Session saved = sessionRepository.save(session);
        sessionParticipantRepository.save(instructor);
        statusChangeRepository.append(
                new SessionStatusChange(TsidGenerator.generate(), saved.id(), null, saved.status(), createdAt));
        return saved;
    }
}
