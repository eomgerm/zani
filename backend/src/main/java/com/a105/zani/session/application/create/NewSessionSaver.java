package com.a105.zani.session.application.create;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.recording.application.orchestrate.EnrollSessionRecordingUseCase;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionRepository;

/**
 * 세션 저장을 매 시도마다 독립된 트랜잭션으로 수행한다.
 *
 * <p>초대 코드 유니크 충돌은 flush 시점에 드러나며, 실패한 트랜잭션의 영속성 컨텍스트는 재사용할 수 없다. 저장을 {@link Propagation#REQUIRES_NEW} 경계로 분리해, 한 시도가
 * 롤백되어도 다음 재시도가 오염되지 않은 트랜잭션에서 실행되도록 보장한다.
 *
 * <p>방 생성 즉시 녹화(Story 15)를 위해, 세션 insert와 같은 트랜잭션에서 녹화 등록 outbox를 기록한다(transactional outbox). 실패한 시도가 롤백되면 outbox 행도 함께
 * 롤백된다.
 */
@Component
class NewSessionSaver {

    private final SessionRepository sessionRepository;
    private final EnrollSessionRecordingUseCase enrollSessionRecordingUseCase;

    NewSessionSaver(SessionRepository sessionRepository, EnrollSessionRecordingUseCase enrollSessionRecordingUseCase) {
        this.sessionRepository = sessionRepository;
        this.enrollSessionRecordingUseCase = enrollSessionRecordingUseCase;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Session save(Session session) {
        Session saved = sessionRepository.save(session);
        enrollSessionRecordingUseCase.enroll(saved.id());
        return saved;
    }
}
