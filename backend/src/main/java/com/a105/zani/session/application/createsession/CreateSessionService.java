package com.a105.zani.session.application.createsession;

import com.a105.zani.common.infrastructure.persistence.TsidGenerator;
import com.a105.zani.session.application.exception.ActiveSessionExistsException;
import com.a105.zani.session.application.port.SessionActivationLockPort;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateSessionService implements CreateSessionUseCase {

    private static final Duration ACTIVATION_TTL = Duration.ofHours(3);
    private static final int INVITE_CODE_LENGTH = 8;
    private static final String INVITE_CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final SessionRepository sessionRepository;
    private final SessionActivationLockPort activationLockPort;
    private final SecureRandom random = new SecureRandom();

    public CreateSessionService(
            SessionRepository sessionRepository,
            SessionActivationLockPort activationLockPort) {
        this.sessionRepository = sessionRepository;
        this.activationLockPort = activationLockPort;
    }

    @Override
    @Transactional
    public CreateSessionResult create(CreateSessionCommand command) {
        if (!activationLockPort.tryAcquire(command.instructorId(), ACTIVATION_TTL)) {
            throw new ActiveSessionExistsException();
        }

        try {
            Session session = Session.start(
                    TsidGenerator.generate(),
                    command.instructorId(),
                    command.title(),
                    generateInviteCode(),
                    Instant.now());
            Session saved = sessionRepository.save(session);
            return new CreateSessionResult(
                    saved.id(), saved.inviteCode(), saved.status(), saved.expiresAt());
        } catch (RuntimeException exception) {
            activationLockPort.release(command.instructorId());
            throw exception;
        }
    }

    private String generateInviteCode() {
        StringBuilder builder = new StringBuilder(INVITE_CODE_LENGTH);
        for (int i = 0; i < INVITE_CODE_LENGTH; i++) {
            builder.append(INVITE_CODE_ALPHABET.charAt(random.nextInt(INVITE_CODE_ALPHABET.length())));
        }
        return builder.toString();
    }
}
