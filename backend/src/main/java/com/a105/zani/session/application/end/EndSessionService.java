package com.a105.zani.session.application.end;

import java.time.Clock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.audioclip.application.releaseaudio.ReleaseInstructorAudioUseCase;
import com.a105.zani.recording.application.stoprecording.StopSessionRecordingUseCase;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.port.MediaRoomControlPort;
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
    private final StopSessionRecordingUseCase stopSessionRecordingUseCase;
    private final MediaRoomControlPort mediaRoomControlPort;

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
        releaseMediaRoom(ended.id());
        log.info("Session {} ended: reason={}", ended.id(), command.reason());
        return new EndSessionResult(ended.id(), ended.status(), true);
    }

    /**
     * 미디어 쪽 뒷정리: 녹화를 멈추고 room 을 닫는다(LIVE-009·LIVE-010).
     *
     * <p><b>순서가 중요하다.</b> room 을 먼저 닫으면 아직 도는 Egress 가 입력을 잃은 채 끝나 파일이 온전히 닫히지 않는다. 녹화를 먼저 멈춘 뒤 room 을 닫는다.
     *
     * <p>room 을 닫지 않으면 이미 발급된 토큰의 TTL(10분) 이 남아 있는 동안 종료된 수업에 다시 들어갈 수 있다.
     *
     * <p>어느 쪽이 실패해도 종료를 되돌리지 않는다. 종료를 롤백하면 수업이 계속 살아 있는 것으로 남는데 그쪽이 더 나쁘고, 남은 room·Egress 는 기록만 남기면 사람이 정리할 수 있다.
     */
    private void releaseMediaRoom(long sessionId) {
        try {
            stopSessionRecordingUseCase.stopRecording(sessionId);
        } catch (RuntimeException exception) {
            log.warn("Failed to stop the recording of session {}", sessionId, exception);
        }
        try {
            if (!mediaRoomControlPort.closeRoom(sessionId)) {
                // 닫히지 않은 room 은 토큰 TTL 동안 재입장 통로로 남는다. 사실을 남겨 사람이 확인할 수 있게 한다.
                log.warn("Media room for session {} was not closed", sessionId);
            }
        } catch (RuntimeException exception) {
            log.warn("Failed to close the media room of session {}", sessionId, exception);
        }
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
