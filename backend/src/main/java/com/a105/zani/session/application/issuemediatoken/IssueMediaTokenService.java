package com.a105.zani.session.application.issuemediatoken;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.member.application.get.GetMemberDisplayNameQuery;
import com.a105.zani.member.application.get.GetMemberDisplayNameUseCase;
import com.a105.zani.session.application.exception.MediaTokenSessionNotFoundException;
import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.SessionAlreadyEndedException;
import com.a105.zani.session.application.exception.SessionNotStartedException;
import com.a105.zani.session.application.port.IssuedMediaToken;
import com.a105.zani.session.application.port.LiveKitTokenPort;
import com.a105.zani.session.application.port.MediaTokenRequest;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

/** 세션 멤버에게 LiveKit 미디어 토큰을 발급한다. identity·roomName은 DB에서 재구성하고, 표시 이름·역할은 서버가 결정한다. 종료된 세션·비멤버·없는 세션은 차단한다. */
@Service
public class IssueMediaTokenService implements IssueMediaTokenUseCase {

    private static final String DEFAULT_DISPLAY_NAME = "참가자";

    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository participantRepository;
    private final GetMemberDisplayNameUseCase getMemberDisplayNameUseCase;
    private final LiveKitTokenPort liveKitTokenPort;

    public IssueMediaTokenService(
            SessionRepository sessionRepository,
            SessionParticipantRepository participantRepository,
            GetMemberDisplayNameUseCase getMemberDisplayNameUseCase,
            LiveKitTokenPort liveKitTokenPort) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.getMemberDisplayNameUseCase = getMemberDisplayNameUseCase;
        this.liveKitTokenPort = liveKitTokenPort;
    }

    @Override
    @Transactional(readOnly = true)
    public IssueMediaTokenResult issue(IssueMediaTokenCommand command) {
        // 멤버십을 먼저 확인해 비멤버에게 세션 존재·종료 상태를 노출하지 않는다.
        SessionParticipant participant = participantRepository
                .findBySessionIdAndUserId(command.sessionId(), command.userId())
                .orElseThrow(NotSessionMemberException::new);

        Session session =
                sessionRepository.findById(command.sessionId()).orElseThrow(MediaTokenSessionNotFoundException::new);
        // ENDING 이후에는 재발급하지 않는다(가이드 §7). 종료 절차 중에 새 토큰이 나가면 정리 중인 Room으로 다시 들어온다.
        if (session.hasStartedEnding()) {
            throw new SessionAlreadyEndedException();
        }
        // 강사는 PREPARING부터 받아야 미디어를 붙이고 수업을 시작할 수 있고, 학생은 LIVE 이후에만 받는다(가이드 §4).
        if (!session.canIssueTokenFor(participant.role())) {
            throw new SessionNotStartedException();
        }

        // 표시 이름은 member 도메인의 읽기 UseCase로만 조회한다(크로스도메인은 공개 API 경유).
        String displayName = getMemberDisplayNameUseCase
                .getDisplayName(new GetMemberDisplayNameQuery(command.userId()))
                .orElse(DEFAULT_DISPLAY_NAME);
        String identity = "p-" + participant.id();

        IssuedMediaToken issued =
                liveKitTokenPort.issue(new MediaTokenRequest(identity, displayName, participant.role(), session.id()));

        // 강의실은 진입 시 이 응답만 받으므로, 자동 종료 예정 시각도 함께 알려 종료 임박 안내를 띄울 수 있게 한다.
        return new IssueMediaTokenResult(
                issued.liveKitUrl(),
                issued.accessToken(),
                issued.roomName(),
                identity,
                issued.expiresAt(),
                // 아직 시작하지 않은 세션에는 자동 종료 시각이 없다. 강사가 시작을 호출하면 그때 정해진다.
                session.expiresAt().orElse(null));
    }
}
