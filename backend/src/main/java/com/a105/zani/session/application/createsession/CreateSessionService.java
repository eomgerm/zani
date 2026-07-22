package com.a105.zani.session.application.createsession;

import java.time.Instant;

import org.springframework.stereotype.Service;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.session.application.exception.ActiveSessionExistsException;
import com.a105.zani.session.application.exception.DuplicateInviteCodeException;
import com.a105.zani.session.application.exception.InviteCodeGenerationFailedException;
import com.a105.zani.session.application.port.SessionActivationLockPort;
import com.a105.zani.session.domain.InviteCodeGenerator;
import com.a105.zani.session.domain.model.Session;

@Service
public class CreateSessionService implements CreateSessionUseCase {

    private static final int MAX_INVITE_CODE_ATTEMPTS = 5;

    private final NewSessionSaver newSessionSaver;
    private final SessionActivationLockPort activationLockPort;
    private final InviteCodeGenerator inviteCodeGenerator;

    public CreateSessionService(
            NewSessionSaver newSessionSaver,
            SessionActivationLockPort activationLockPort,
            InviteCodeGenerator inviteCodeGenerator) {
        this.newSessionSaver = newSessionSaver;
        this.activationLockPort = activationLockPort;
        this.inviteCodeGenerator = inviteCodeGenerator;
    }

    @Override
    public CreateSessionResult create(CreateSessionCommand command) {
        if (!activationLockPort.tryAcquire(command.instructorId(), Session.ACTIVE_DURATION)) {
            throw new ActiveSessionExistsException();
        }

        try {
            Session saved = saveWithInviteCodeRetry(command);
            return new CreateSessionResult(saved.id(), saved.inviteCode(), saved.status(), saved.expiresAt());
        } catch (RuntimeException exception) {
            try {
                activationLockPort.release(command.instructorId());
            } catch (RuntimeException releaseException) {
                exception.addSuppressed(releaseException);
            }
            throw exception;
        }
    }

    private Session saveWithInviteCodeRetry(CreateSessionCommand command) {
        long id = TsidGenerator.generate();
        Instant startedAt = Instant.now();
        for (int attempt = 0; attempt < MAX_INVITE_CODE_ATTEMPTS; attempt++) {
            Session session = Session.start(
                    id, command.instructorId(), command.title(), inviteCodeGenerator.generate(), startedAt);
            try {
                return newSessionSaver.save(session);
            } catch (DuplicateInviteCodeException collision) {
                // 초대 코드가 충돌한 경우에만 새 코드로 재시도한다.
                // 각 저장은 REQUIRES_NEW 트랜잭션이라, 실패한 시도가 롤백돼도 다음 시도에 영향을 주지 않는다.
            }
        }
        throw new InviteCodeGenerationFailedException();
    }
}
