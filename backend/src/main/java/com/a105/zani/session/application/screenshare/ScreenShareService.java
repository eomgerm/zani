package com.a105.zani.session.application.screenshare;

import java.time.Duration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.ScreenShareInUseException;
import com.a105.zani.session.application.exception.SessionAlreadyEndedException;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.port.ActiveScreenSharePort;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

/**
 * 화면 공유 시작·종료를 조정한다. 역할 제한은 없고(2026-07-30 확정), 세션당 활성 공유 1명 규칙만 서버가 강제한다.
 *
 * <p>"누가 공유 중인가"의 브로드캐스트는 LiveKit 트랙 시그널링(TrackPublished/Unpublished)이 참가자들에게 native로 전달하므로 여기서 따로 다루지 않는다. 이 서비스는 시작
 * 순간의 경합을 원자적으로 중재하고(활성 슬롯), 종료·퇴장·재연결 시 슬롯을 정리하는 서버 권위만 담당한다.
 */
@Service
public class ScreenShareService implements StartScreenShareUseCase, StopScreenShareUseCase {

    /**
     * 활성 공유 슬롯 TTL. 공유자 FE가 공유 중 이보다 짧은 주기로 갱신(claim)해 TTL을 늘리므로, 갱신이 끊기면(크래시·강제 종료·긴 네트워크 단절) 슬롯이 이 시간 안에 비워져 다음 사람이
     * 공유할 수 있다. 정상 종료·퇴장은 명시적 release로 즉시 비운다.
     */
    private static final Duration ACTIVE_SHARE_TTL = Duration.ofSeconds(30);

    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository participantRepository;
    private final ActiveScreenSharePort activeScreenSharePort;

    public ScreenShareService(
            SessionRepository sessionRepository,
            SessionParticipantRepository participantRepository,
            ActiveScreenSharePort activeScreenSharePort) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.activeScreenSharePort = activeScreenSharePort;
    }

    @Override
    @Transactional(readOnly = true)
    public StartScreenShareResult start(StartScreenShareCommand command) {
        // 멤버십을 먼저 확인해 비멤버에게 세션 존재·상태를 노출하지 않는다(미디어 토큰 발급과 같은 순서).
        SessionParticipant participant = participantRepository
                .findBySessionIdAndUserId(command.sessionId(), command.userId())
                .orElseThrow(NotSessionMemberException::new);

        Session session = sessionRepository.findById(command.sessionId()).orElseThrow(SessionNotFoundException::new);
        if (session.isEnded()) {
            throw new SessionAlreadyEndedException();
        }

        // 슬롯이 비어 있거나 이미 내가 공유 중이면 성공(TTL 갱신). 다른 참가자가 공유 중이면 거부한다.
        if (!activeScreenSharePort.claim(command.sessionId(), participant.id(), ACTIVE_SHARE_TTL)) {
            throw new ScreenShareInUseException();
        }
        return new StartScreenShareResult(participant.id());
    }

    @Override
    @Transactional(readOnly = true)
    public void stop(StopScreenShareCommand command) {
        SessionParticipant participant = participantRepository
                .findBySessionIdAndUserId(command.sessionId(), command.userId())
                .orElseThrow(NotSessionMemberException::new);
        // 내 슬롯일 때만 비운다. 이미 다른 사람이 공유 중이면(내 공유가 정리된 뒤) 그 슬롯은 건드리지 않는다 — 멱등.
        activeScreenSharePort.release(command.sessionId(), participant.id());
    }
}
