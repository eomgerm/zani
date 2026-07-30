package com.a105.zani.session.application.issuemediatoken;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.member.application.get.GetMemberDisplayNameQuery;
import com.a105.zani.member.application.get.GetMemberDisplayNameUseCase;
import com.a105.zani.session.application.exception.MediaTokenSessionNotFoundException;
import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.SessionAlreadyEndedException;
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
        // PREPARING 도 허용한다. 강사는 수업을 시작하기 전에 방에 들어가 카메라·마이크를 맞춰야 하고, 그러려면 토큰이 필요하다.
        // 막아야 하는 건 종료 절차에 들어간 세션이다 — 정리 중인 방에 새 연결을 들이면 정리가 끝나지 않는다.
        if (session.isClosed()) {
            throw new SessionAlreadyEndedException();
        }

        // 표시 이름은 member 도메인의 읽기 UseCase로만 조회한다(크로스도메인은 공개 API 경유).
        String displayName = getMemberDisplayNameUseCase
                .getDisplayName(new GetMemberDisplayNameQuery(command.userId()))
                .orElse(DEFAULT_DISPLAY_NAME);
        String identity = "p-" + participant.id();

        IssuedMediaToken issued =
                liveKitTokenPort.issue(new MediaTokenRequest(identity, displayName, participant.role(), session.id()));

        // 강의실은 진입 시 이 응답만 받으므로, 자동 종료 예정 시각과 강의명도 함께 내린다.
        return new IssueMediaTokenResult(
                issued.liveKitUrl(),
                issued.accessToken(),
                issued.roomName(),
                identity,
                issued.expiresAt(),
                session.expiresAt(),
                session.title());
    }
}
