package com.a105.zani.session.application.attendance;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.session.domain.model.ParticipantIdentity;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;

/**
 * 미디어 서버가 알려온 입·이탈을 출석 기록으로 남긴다.
 *
 * <p>모르는 identity 는 조용히 버린다. Egress·시스템 참가자가 같은 방에 들어오는데 그들은 인원과 출석에서 빠져야 하고, 예외를 던지면 webhook 이 5xx 를 받아 무한히 재전송된다.
 *
 * <p>시각은 미디어 서버가 준 값을 쓴다. 서버 현재 시각을 쓰면 webhook 이 늦게 도착하거나 재전송될 때마다 출석 시각이 뒤로 밀린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordMediaAttendanceService implements RecordMediaAttendanceUseCase {

    private final SessionParticipantRepository participantRepository;

    @Override
    @Transactional
    public void record(RecordMediaAttendanceCommand command) {
        Optional<SessionParticipant> found = ParticipantIdentity.parse(command.participantIdentity())
                .flatMap(participantRepository::findById)
                .filter(participant -> participant.sessionId().equals(command.sessionId()));
        if (found.isEmpty()) {
            // Egress·시스템 참가자거나 다른 세션의 identity 다. 출석 대상이 아니다.
            log.debug(
                    "Media attendance for unknown identity {} in session {}",
                    command.participantIdentity(),
                    command.sessionId());
            return;
        }

        SessionParticipant participant = found.get();
        if (command.joined()) {
            // 재전송된 입장 이벤트가 최초 입장 시각을 밀지 않는다(도메인이 한 번만 심는다).
            participant.recordMediaJoin(command.occurredAt());
        } else {
            participant.recordMediaLeave(command.occurredAt());
        }
        participantRepository.save(participant);
    }
}
