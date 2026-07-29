package com.a105.zani.session.application.create;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.session.application.exception.ActiveSessionExistsException;
import com.a105.zani.session.application.exception.DuplicateInviteCodeException;
import com.a105.zani.session.application.exception.InviteCodeGenerationFailedException;
import com.a105.zani.session.application.port.SessionActivationLockPort;
import com.a105.zani.session.domain.InviteCodeGenerator;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * 강사가 수업 방을 준비한다. 세션은 {@code PREPARING}으로 만들어지고, 강사가 미디어를 붙인 뒤 시작을 호출해야 {@code LIVE}가 된다(가이드 §5).
 *
 * <p>강사 참가 관계도 이때 함께 만든다. 미디어 토큰 발급과 presence heartbeat가 모두 참가 관계로 멤버십을 확인하므로, 이 행이 없으면 강사가 자기 수업에 들어갈 수 없다.
 */
@Service
public class CreateSessionService implements CreateSessionUseCase {

    private static final int MAX_INVITE_CODE_ATTEMPTS = 5;

    private final NewSessionSaver newSessionSaver;
    private final SessionActivationLockPort activationLockPort;
    private final InviteCodeGenerator inviteCodeGenerator;
    private final Clock clock;

    public CreateSessionService(
            NewSessionSaver newSessionSaver,
            SessionActivationLockPort activationLockPort,
            InviteCodeGenerator inviteCodeGenerator,
            Clock clock) {
        this.newSessionSaver = newSessionSaver;
        this.activationLockPort = activationLockPort;
        this.inviteCodeGenerator = inviteCodeGenerator;
        this.clock = clock;
    }

    @Override
    public CreateSessionResult create(CreateSessionCommand command) {
        if (!activationLockPort.tryAcquire(command.instructorId(), Session.ACTIVE_DURATION)) {
            throw new ActiveSessionExistsException();
        }

        try {
            Session saved = saveWithInviteCodeRetry(command);
            return new CreateSessionResult(
                    saved.id(),
                    saved.inviteCode(),
                    saved.status(),
                    saved.expiresAt().orElse(null));
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
        long sessionId = TsidGenerator.generate();
        long participantId = TsidGenerator.generate();
        Instant enrolledAt = clock.instant();
        for (int attempt = 0; attempt < MAX_INVITE_CODE_ATTEMPTS; attempt++) {
            Session session =
                    Session.prepare(sessionId, command.instructorId(), command.title(), inviteCodeGenerator.generate());
            // 강사의 LiveKit identity(p-{participantId})가 재시도마다 바뀌지 않도록 ID는 루프 밖에서 한 번만 만든다.
            SessionParticipant instructor = SessionParticipant.enroll(
                    participantId, sessionId, command.instructorId(), SessionParticipantRole.INSTRUCTOR, enrolledAt);
            try {
                return newSessionSaver.save(session, instructor, enrolledAt);
            } catch (DuplicateInviteCodeException collision) {
                // 초대 코드가 충돌한 경우에만 새 코드로 재시도한다.
                // 각 저장은 REQUIRES_NEW 트랜잭션이라, 실패한 시도가 롤백돼도 다음 시도에 영향을 주지 않는다.
            }
        }
        throw new InviteCodeGenerationFailedException();
    }
}
