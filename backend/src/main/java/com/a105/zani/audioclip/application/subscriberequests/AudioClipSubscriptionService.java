package com.a105.zani.audioclip.application.subscriberequests;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.a105.zani.audioclip.application.exception.AudioClipSessionEndedException;
import com.a105.zani.audioclip.application.exception.AudioClipSessionNotFoundException;
import com.a105.zani.audioclip.application.exception.NotSessionInstructorException;
import com.a105.zani.audioclip.application.port.AudioClipRequestStorePort;
import com.a105.zani.audioclip.domain.model.AudioClipRequest;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

/** 클립 요청 스트림 구독 자격을 검증하고, 재구독 리플레이 대상(PENDING·미만료)을 조회한다. 상태를 바꾸지 않는 읽기 전용이며 요청 기록은 Redis 에 있으므로 DB 트랜잭션을 쓰지 않는다. */
@Service
@RequiredArgsConstructor
public class AudioClipSubscriptionService implements SubscribeAudioClipRequestsUseCase {

    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository participantRepository;
    private final AudioClipRequestStorePort requestStore;
    private final Clock clock;

    @Override
    public SubscribeAudioClipRequestsResult subscribe(SubscribeAudioClipRequestsQuery query) {
        // 멤버가 아니거나 강사가 아니면 같은 403 — 스트림 존재 여부를 노출하지 않는다.
        SessionParticipant participant = participantRepository
                .findBySessionIdAndUserId(query.sessionId(), query.userId())
                .orElseThrow(NotSessionInstructorException::new);
        if (participant.role() != SessionParticipantRole.INSTRUCTOR) {
            throw new NotSessionInstructorException();
        }

        Session session =
                sessionRepository.findById(query.sessionId()).orElseThrow(AudioClipSessionNotFoundException::new);
        if (session.isEnded()) {
            throw new AudioClipSessionEndedException();
        }

        Instant now = clock.instant();
        List<AudioClipRequest> pending = requestStore.findPending(query.sessionId()).stream()
                .filter(request -> !request.isExpired(now))
                .toList();
        return new SubscribeAudioClipRequestsResult(pending);
    }
}
