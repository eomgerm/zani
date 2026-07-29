package com.a105.zani.session.application.trackmediaconnection;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

/**
 * LiveKit 연결 이벤트를 참가 관계에 반영한다.
 *
 * <p>모르는 identity는 조용히 버린다. Egress·Agent 같은 시스템 참가자는 {@code p-{id}} 형식이 아니라 자연히 걸러지고(가이드 §11: 시스템 참가자는 인원·출석에서 제외), 형식만
 * 맞고 실체가 없는 identity는 위조 시도이거나 다른 세션의 토큰 재사용이므로 출석을 주면 안 된다.
 *
 * <p>중복·역순 webhook에 안전하다. 최초 입장 시각은 {@link SessionParticipant#confirmMediaJoin}이 한 번만 확정하고, 이탈 기록은 자격을 되돌리지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaConnectionTrackingService implements TrackMediaConnectionUseCase {

    private static final String IDENTITY_PREFIX = "p-";

    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository participantRepository;

    @Override
    @Transactional
    public boolean confirmJoined(MediaConnectionCommand command) {
        Optional<SessionParticipant> found = resolve(command);
        if (found.isEmpty()) {
            return false;
        }
        // 종료 절차에 들어간 세션에 뒤늦게 붙는 건 기존 토큰 재사용이다(가이드 §12). 출석을 주지 않는다.
        // Room에서 강제로 끊는 것은 RoomService 연동이 붙는 시작·종료 유스케이스의 몫이다.
        Optional<Session> session = sessionRepository.findById(command.sessionId());
        if (session.isEmpty() || session.get().hasStartedEnding()) {
            log.warn(
                    "Ignoring participant_joined for session {} that is no longer open, identity={}",
                    command.sessionId(),
                    command.participantIdentity());
            return false;
        }

        SessionParticipant participant = found.get();
        boolean firstJoin = participant.confirmMediaJoin(command.occurredAt());
        participantRepository.save(participant);
        if (firstJoin) {
            log.info("Participant {} confirmed first media join in session {}", participant.id(), command.sessionId());
        }
        return firstJoin;
    }

    @Override
    @Transactional
    public void recordLeft(MediaConnectionCommand command) {
        resolve(command).ifPresent(participant -> {
            participant.recordMediaLeft(command.occurredAt());
            participantRepository.save(participant);
        });
    }

    /** identity를 참가 관계로 해석한다. 다른 세션의 참가자 ID를 들고 오는 경우를 막기 위해 세션 일치까지 확인한다. */
    private Optional<SessionParticipant> resolve(MediaConnectionCommand command) {
        if (command.sessionId() == null) {
            return Optional.empty();
        }
        Optional<SessionParticipant> participant =
                parseParticipantId(command.participantIdentity()).flatMap(participantRepository::findById);
        if (participant.isEmpty()) {
            log.debug(
                    "Unknown participant identity {} in session {}",
                    command.participantIdentity(),
                    command.sessionId());
            return Optional.empty();
        }
        if (!command.sessionId().equals(participant.get().sessionId())) {
            log.warn(
                    "Participant identity {} does not belong to session {}",
                    command.participantIdentity(),
                    command.sessionId());
            return Optional.empty();
        }
        return participant;
    }

    private static Optional<Long> parseParticipantId(String identity) {
        if (identity == null || !identity.startsWith(IDENTITY_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(identity.substring(IDENTITY_PREFIX.length())));
        } catch (NumberFormatException notOurIdentity) {
            return Optional.empty();
        }
    }
}
