package com.a105.zani.session.application.end;

import java.time.Clock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.audioclip.application.releaseaudio.ReleaseInstructorAudioUseCase;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.port.SessionActivationLockPort;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class EndSessionService implements EndSessionUseCase {

    private final SessionRepository sessionRepository;
    private final ReleaseInstructorAudioUseCase releaseInstructorAudioUseCase;
    private final SessionActivationLockPort activationLockPort;

    /** 종료 시각의 출처. 도메인이 시계를 읽지 않도록 서비스가 주입받아 넘긴다. */
    private final Clock clock;

    @Override
    @Transactional
    public EndSessionResult end(EndSessionCommand command) {
        Session session = sessionRepository.findById(command.sessionId()).orElseThrow(SessionNotFoundException::new);
        if (session.isEnded()) {
            // 이미 종료된 세션은 그대로 둔다(중복 종료 요청·재시도에 멱등).
            return new EndSessionResult(session.id(), session.status(), false);
        }
        session.end(clock.instant());
        Session ended = sessionRepository.save(session);
        // 코칭 오디오 버퍼는 세션당 수십 MB를 잡고 있어 종료 시 반납해야 한다. 메모리 조작뿐이라
        // 실패해도 종료를 되돌릴 이유가 없고, 되돌아가더라도 스트림이 다시 채운다.
        releaseInstructorAudioUseCase.release(ended.id());
        releaseActivationLock(ended.instructorId());
        log.info("Session {} ended: reason={}", ended.id(), command.reason());
        return new EndSessionResult(ended.id(), ended.status(), true);
    }

    /**
     * 다음 수업을 열 수 있도록 활성 잠금을 반납한다.
     *
     * <p>잠금은 3시간 TTL 이라, 반납하지 않으면 수업을 끝낸 강사가 그 시간 동안 "이미 진행 중인 수업이 있어요" 로 막힌다. 종료가 곧 다음 수업을 열 수 있게 되는 시점이다.
     *
     * <p>실패해도 종료를 되돌리지 않는다. 잠금이 남는 최악의 경우는 TTL 만큼 기다리면 풀리지만, 종료를 롤백하면 수업이 계속 살아 있는 것으로 남는다 — 그쪽이 더 나쁘다.
     */
    private void releaseActivationLock(long instructorId) {
        try {
            activationLockPort.release(instructorId);
        } catch (RuntimeException exception) {
            log.warn("Failed to release the activation lock for instructor {}", instructorId, exception);
        }
    }
}
