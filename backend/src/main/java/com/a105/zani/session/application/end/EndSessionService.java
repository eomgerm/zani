package com.a105.zani.session.application.end;

import java.time.Clock;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.audioclip.application.releaseaudio.ReleaseInstructorAudioUseCase;
import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.port.SessionActivationLockPort;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.model.SessionStatusChange;
import com.a105.zani.session.domain.repository.SessionRepository;
import com.a105.zani.session.domain.repository.SessionStatusChangeRepository;

/**
 * 세션 종료의 단일 경로. 강사 명시 종료·3시간 초과·강사 미복귀·준비 상태 방치가 모두 여기로 모인다(가이드 §12).
 *
 * <p>종료는 {@code ENDING}을 거쳐 {@code NOTE_PENDING}까지 한 번에 진행한다. {@code ENDING}은 "신규 입장·토큰 발급을 막은 뒤 Egress와 Room을 정리하는
 * 구간"이고, 그 정리가 끝나야 강사 메모 대기로 넘어간다. Egress 종료와 Room 삭제 연동은 아직 없으므로 지금은 두 전이가 연달아 일어나며, 연동이 붙으면 그 사이에 들어간다.
 *
 * <p>멱등하다. 이미 종료 절차에 들어간 세션은 상태를 건드리지 않고 {@code ended=false}로 답한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EndSessionService implements EndSessionUseCase {

    private final SessionRepository sessionRepository;
    private final SessionStatusChangeRepository statusChangeRepository;
    private final SessionActivationLockPort activationLockPort;
    private final ReleaseInstructorAudioUseCase releaseInstructorAudioUseCase;
    private final Clock clock;

    @Override
    @Transactional
    public EndSessionResult end(EndSessionCommand command) {
        Session session = sessionRepository.findById(command.sessionId()).orElseThrow(SessionNotFoundException::new);
        SessionStatus before = session.status();
        Instant now = clock.instant();

        if (!session.beginEnding(command.reason(), now)) {
            // 이미 종료된 세션은 그대로 둔다(중복 종료 요청·재시도에 멱등).
            return new EndSessionResult(session.id(), session.status(), false);
        }
        appendStatusChange(session.id(), before, SessionStatus.ENDING, now);

        session.markNotePending(now);
        appendStatusChange(session.id(), SessionStatus.ENDING, session.status(), now);

        Session ended = sessionRepository.save(session);
        releaseActivationLock(ended);
        // 코칭 오디오 버퍼는 세션당 수십 MB를 잡고 있어 종료 시 반납해야 한다. 메모리 조작뿐이라
        // 실패해도 종료를 되돌릴 이유가 없고, 되돌아가더라도 스트림이 다시 채운다.
        releaseInstructorAudioUseCase.release(ended.id());
        log.info("Session {} ended: reason={}, status={}", ended.id(), command.reason(), ended.status());
        return new EndSessionResult(ended.id(), ended.status(), true);
    }

    /**
     * 강사가 곧바로 다음 수업을 열 수 있게 활성 세션 잠금을 반납한다.
     *
     * <p>잠금은 최대 수업 시간(3시간) TTL로 잡히므로, 반납하지 않으면 10분짜리 수업을 끝낸 강사가 2시간 50분 동안 새 수업을 만들지 못한다. 실패하더라도 종료는 이미 DB에 확정됐고 잠금은
     * TTL로 사라지므로 되돌리지 않는다.
     */
    private void releaseActivationLock(Session ended) {
        try {
            activationLockPort.release(ended.instructorId());
        } catch (RuntimeException lockUnavailable) {
            log.warn(
                    "Failed to release the activation lock of instructor {} after ending session {}; it will expire on its own",
                    ended.instructorId(),
                    ended.id(),
                    lockUnavailable);
        }
    }

    private void appendStatusChange(Long sessionId, SessionStatus from, SessionStatus to, Instant at) {
        statusChangeRepository.append(new SessionStatusChange(TsidGenerator.generate(), sessionId, from, to, at));
    }
}
