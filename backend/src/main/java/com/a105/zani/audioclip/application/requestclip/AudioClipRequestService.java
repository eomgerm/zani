package com.a105.zani.audioclip.application.requestclip;

import java.time.Clock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.audioclip.application.exception.AudioClipSessionEndedException;
import com.a105.zani.audioclip.application.exception.AudioClipSessionNotFoundException;
import com.a105.zani.audioclip.application.port.AudioClipDispatchPort;
import com.a105.zani.audioclip.application.port.AudioClipRequestStorePort;
import com.a105.zani.audioclip.domain.model.AudioClipRequest;
import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionRepository;

/** 진행 중인 세션에 대해 클립 요청을 만들어 저장하고 강사 클라이언트로 전달한다. 요청 상태는 Redis 에만 있으므로 DB 트랜잭션을 사용하지 않는다(세션 조회는 단건 읽기뿐). */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioClipRequestService implements RequestAudioClipUseCase {

    private final SessionRepository sessionRepository;
    private final AudioClipRequestStorePort requestStore;
    private final AudioClipDispatchPort dispatchPort;
    private final Clock clock;

    @Override
    public RequestAudioClipResult request(RequestAudioClipCommand command) {
        Session session =
                sessionRepository.findById(command.sessionId()).orElseThrow(AudioClipSessionNotFoundException::new);
        if (session.isEnded()) {
            throw new AudioClipSessionEndedException();
        }

        AudioClipRequest request = AudioClipRequest.create(TsidGenerator.generate(), session.id(), clock.instant());
        requestStore.save(request);
        // 전달 실패는 요청을 무효화하지 않는다 — 저장된 PENDING 요청은 재구독 리플레이로 회복된다.
        dispatchPort.dispatch(request);

        log.info("Audio clip {} requested for session {}", request.clipId(), session.id());
        return new RequestAudioClipResult(
                request.clipId(), session.id(), AudioClipRequest.CLIP_WINDOW.toSeconds(), request.expiresAt());
    }
}
