package com.a105.zani.session.application.end;

import java.time.Clock;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.audioclip.application.releaseaudio.ReleaseInstructorAudioUseCase;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.port.SessionActivationLockPort;
import com.a105.zani.session.application.port.SessionStatusHistoryPort;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.repository.SessionRepository;

/**
 * 수업 종료. 강사 명시 종료·3시간 자동 종료·강사 5분 미복귀가 모두 이 유스케이스를 쓴다(가이드 §12의 단일 종료 경로).
 *
 * <p>종료는 두 단계다. 먼저 ENDING 으로 옮겨 새 입장·미디어 발급을 닫고, 정리(오디오 버퍼 반납·활성 잠금 반납)를 마친 뒤 NOTE_PENDING 으로 넘겨 강사 메모를 기다린다. 두 단계를 한
 * 트랜잭션에서 처리하므로 "정리 중" 상태로 멈춰 있는 세션은 남지 않는다. 단계를 나눈 이유는 정리 도중에 들어온 입장·토큰 요청을 거절할 근거가 필요하기 때문이다.
 *
 * <p>멱등하다. 이미 종료 절차에 들어간 세션에 대한 재요청은 상태를 건드리지 않고 {@code ended=false} 로 답한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EndSessionService implements EndSessionUseCase {

    private final SessionRepository sessionRepository;
    private final ReleaseInstructorAudioUseCase releaseInstructorAudioUseCase;
    private final SessionActivationLockPort activationLockPort;
    private final SessionStatusHistoryPort statusHistoryPort;
    private final Clock clock;

    @Override
    @Transactional
    public EndSessionResult end(EndSessionCommand command) {
        Session session = sessionRepository.findById(command.sessionId()).orElseThrow(SessionNotFoundException::new);

        SessionStatus beforeEnding = session.status();
        Instant endedAt = clock.instant();
        if (!session.beginEnding(endedAt, command.reason())) {
            // 이미 종료 절차에 들어간 세션은 그대로 둔다(중복 종료 요청·재시도에 멱등).
            return new EndSessionResult(session.id(), session.status(), false);
        }
        statusHistoryPort.record(session.id(), beforeEnding, session.status(), endedAt);

        // 코칭 오디오 버퍼는 세션당 수십 MB를 잡고 있어 종료 시 반납해야 한다. 메모리 조작뿐이라
        // 실패해도 종료를 되돌릴 이유가 없고, 되돌아가더라도 스트림이 다시 채운다.
        releaseInstructorAudioUseCase.release(session.id());
        releaseActivationLock(session.instructorId());

        if (session.awaitNote()) {
            statusHistoryPort.record(session.id(), SessionStatus.ENDING, session.status(), endedAt);
        }
        Session ended = sessionRepository.save(session);
        log.info("Session {} ended: reason={}, status={}", ended.id(), command.reason(), ended.status());
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
