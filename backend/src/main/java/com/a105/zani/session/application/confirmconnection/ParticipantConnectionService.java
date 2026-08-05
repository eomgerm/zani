package com.a105.zani.session.application.confirmconnection;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;

/**
 * 실제 미디어 연결을 사후 자료 접근 자격으로 확정한다.
 *
 * <p>알 수 없는 참가자는 예외 대신 경고로 끝낸다. 이 UseCase 의 호출자는 LiveKit webhook 이고, 예외를 던지면 5xx 가 나가 LiveKit 이 같은 통지를 무한히 재전송한다. 자격을 줄
 * 대상이 없다는 사실은 재전송으로 해결되지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipantConnectionService implements ConfirmParticipantConnectionUseCase {

    private final SessionParticipantRepository participantRepository;

    @Override
    @Transactional
    public void confirm(ConfirmParticipantConnectionCommand command) {
        // 세션 대조를 거른다: 통지의 세션과 다른 세션의 참가자 ID 가 오면 남의 수업 자격이 생긴다.
        Optional<SessionParticipant> participant = participantRepository
                .findById(command.participantId())
                .filter(found -> found.sessionId().equals(command.sessionId()));
        if (participant.isEmpty()) {
            log.warn(
                    "Connection confirmed for unknown participant {} in session {}",
                    command.participantId(),
                    command.sessionId());
            return;
        }
        // 이미 자격이 있으면 저장하지 않는다. 재접속·중복 통지가 흔한 경로라 매번 UPDATE 를 돌릴 이유가 없다.
        if (participant.get().confirmConnection(command.connectedAt())) {
            participantRepository.save(participant.get());
        }
    }
}
