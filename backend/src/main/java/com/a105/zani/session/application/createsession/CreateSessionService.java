package com.a105.zani.session.application.createsession;

import com.a105.zani.common.infrastructure.persistence.TsidGenerator;
import com.a105.zani.session.application.exception.ActiveSessionExistsException;
import com.a105.zani.session.application.exception.DuplicateInviteCodeException;
import com.a105.zani.session.application.exception.InviteCodeGenerationFailedException;
import com.a105.zani.session.application.port.SessionActivationLockPort;
import com.a105.zani.session.domain.InviteCodeGenerator;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateSessionService implements CreateSessionUseCase {

    private static final Duration ACTIVATION_TTL = Duration.ofHours(3);
    private static final int MAX_INVITE_CODE_ATTEMPTS = 5;

    private final SessionRepository sessionRepository;
    private final SessionActivationLockPort activationLockPort;
    private final InviteCodeGenerator inviteCodeGenerator;

    public CreateSessionService(
            SessionRepository sessionRepository,
            SessionActivationLockPort activationLockPort,
            InviteCodeGenerator inviteCodeGenerator) {
        this.sessionRepository = sessionRepository;
        this.activationLockPort = activationLockPort;
        this.inviteCodeGenerator = inviteCodeGenerator;
    }

    @Override
    @Transactional
    public CreateSessionResult create(CreateSessionCommand command) {
        if (!activationLockPort.tryAcquire(command.instructorId(), ACTIVATION_TTL)) {
            throw new ActiveSessionExistsException();
        }

        try {
            Session saved = saveWithInviteCodeRetry(command);
            return new CreateSessionResult(
                    saved.id(), saved.inviteCode(), saved.status(), saved.expiresAt());
        } catch (RuntimeException exception) {
            activationLockPort.release(command.instructorId());
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
                return sessionRepository.save(session);
            } catch (DuplicateInviteCodeException collision) {
                // 초대 코드가 충돌한 경우에만 새 코드로 재시도한다.
            }
        }
        throw new InviteCodeGenerationFailedException();
    }
}
