package com.a105.zani.session.application.create;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionRepository;

/**
 * 세션 저장을 매 시도마다 독립된 트랜잭션으로 수행한다.
 *
 * <p>초대 코드 유니크 충돌은 flush 시점에 드러나며, 실패한 트랜잭션의 영속성 컨텍스트는 재사용할 수 없다. 저장을 {@link Propagation#REQUIRES_NEW} 경계로 분리해, 한 시도가
 * 롤백되어도 다음 재시도가 오염되지 않은 트랜잭션에서 실행되도록 보장한다.
 */
@Component
class NewSessionSaver {

    private final SessionRepository sessionRepository;

    NewSessionSaver(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Session save(Session session) {
        return sessionRepository.save(session);
    }
}
