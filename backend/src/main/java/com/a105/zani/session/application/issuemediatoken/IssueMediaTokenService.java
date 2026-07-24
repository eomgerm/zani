package com.a105.zani.session.application.issuemediatoken;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.session.application.exception.MediaTokenSessionNotFoundException;
import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.SessionAlreadyEndedException;
import com.a105.zani.session.application.port.IssuedMediaToken;
import com.a105.zani.session.application.port.LiveKitTokenPort;
import com.a105.zani.session.application.port.MediaTokenRequest;
import com.a105.zani.session.application.port.MemberDisplayNamePort;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

/** 세션 멤버에게 LiveKit 미디어 토큰을 발급한다. identity·roomName은 DB에서 재구성하고, 표시 이름·역할은 서버가 결정한다. 종료된 세션·비멤버·없는 세션은 차단한다. */
@Service
public class IssueMediaTokenService implements IssueMediaTokenUseCase {

    private static final String DEFAULT_DISPLAY_NAME = "참가자";

    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository participantRepository;
    private final MemberDisplayNamePort memberDisplayNamePort;
    private final LiveKitTokenPort liveKitTokenPort;

    public IssueMediaTokenService(
            SessionRepository sessionRepository,
            SessionParticipantRepository participantRepository,
            MemberDisplayNamePort memberDisplayNamePort,
            LiveKitTokenPort liveKitTokenPort) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.memberDisplayNamePort = memberDisplayNamePort;
        this.liveKitTokenPort = liveKitTokenPort;
    }

    @Override
    @Transactional(readOnly = true)
    public IssueMediaTokenResult issue(IssueMediaTokenCommand command) {
        Session session =
                sessionRepository.findById(command.sessionId()).orElseThrow(MediaTokenSessionNotFoundException::new);
        if (session.status() == SessionStatus.ENDED) {
            throw new SessionAlreadyEndedException();
        }

        SessionParticipant participant = participantRepository
                .findBySessionIdAndUserId(command.sessionId(), command.userId())
                .orElseThrow(NotSessionMemberException::new);

        String displayName =
                memberDisplayNamePort.findDisplayName(command.userId()).orElse(DEFAULT_DISPLAY_NAME);
        String identity = "p-" + participant.id();

        IssuedMediaToken issued =
                liveKitTokenPort.issue(new MediaTokenRequest(identity, displayName, participant.role(), session.id()));

        return new IssueMediaTokenResult(
                issued.liveKitUrl(), issued.accessToken(), issued.roomName(), identity, issued.expiresAt());
    }
}
