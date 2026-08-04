package com.a105.zani.session.application.end;

import java.time.Clock;
import java.util.concurrent.Executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.a105.zani.audioclip.application.releaseaudio.ReleaseInstructorAudioUseCase;
import com.a105.zani.recording.application.stoprecording.StopSessionRecordingUseCase;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.port.MediaRoomControlPort;
import com.a105.zani.session.application.port.SessionActivationLockPort;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionRepository;

@Slf4j
@Service
public class EndSessionService implements EndSessionUseCase {

    private final SessionRepository sessionRepository;
    private final ReleaseInstructorAudioUseCase releaseInstructorAudioUseCase;
    private final SessionActivationLockPort activationLockPort;
    private final StopSessionRecordingUseCase stopSessionRecordingUseCase;
    private final MediaRoomControlPort mediaRoomControlPort;

    /** 종료 시각의 출처. 도메인이 시계를 읽지 않도록 서비스가 주입받아 넘긴다. */
    private final Clock clock;

    /** DB 상태 전이만 트랜잭션으로 묶기 위한 템플릿. LiveKit 정리(최대 60초 HTTP)를 트랜잭션 밖에 두려는 것이다. */
    private final TransactionTemplate transactionTemplate;

    /** 미디어 뒷정리 전용 executor. LiveKit 호출을 호출 스레드에서 떼어낸다. */
    private final Executor mediaCleanupExecutor;

    /**
     * {@code Executor} 타입 빈이 둘(코칭 팁 생성·이 뒷정리)이라 어느 쪽인지 {@link Qualifier} 로 못박는다. Lombok 생성자를 쓰지 않는 이유가 이것이다 —
     * {@code lombok.config} 가 없어 필드에 붙인 애노테이션이 생성자로 복사되지 않고, 이름으로 맞추는 방식은 필드명을 바꾸는 순간 기동이 깨진다. coach 의
     * {@code GenerateCoachingTipService} 도 같은 이유로 생성자를 직접 쓴다.
     */
    public EndSessionService(
            SessionRepository sessionRepository,
            ReleaseInstructorAudioUseCase releaseInstructorAudioUseCase,
            SessionActivationLockPort activationLockPort,
            StopSessionRecordingUseCase stopSessionRecordingUseCase,
            MediaRoomControlPort mediaRoomControlPort,
            Clock clock,
            TransactionTemplate transactionTemplate,
            @Qualifier("mediaCleanupExecutor") Executor mediaCleanupExecutor) {
        this.sessionRepository = sessionRepository;
        this.releaseInstructorAudioUseCase = releaseInstructorAudioUseCase;
        this.activationLockPort = activationLockPort;
        this.stopSessionRecordingUseCase = stopSessionRecordingUseCase;
        this.mediaRoomControlPort = mediaRoomControlPort;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
        this.mediaCleanupExecutor = mediaCleanupExecutor;
    }

    @Override
    public EndSessionResult end(EndSessionCommand command) {
        EndSessionResult result = transactionTemplate.execute(status -> endInTransaction(command));
        if (result.ended()) {
            log.info("Session {} ended: reason={}", result.sessionId(), command.reason());
            // LiveKit 정리는 커밋 뒤에, 그리고 호출 스레드가 아닌 곳에서 한다.
            //
            // 커밋 뒤인 이유: 트랜잭션 안에서 하면 미디어 서버가 앓는 동안 DB 커넥션이 그만큼 붙잡히고,
            // 커밋이 실패했는데 녹화·room 만 먼저 정리되는 역전도 생긴다.
            //
            // 호출 스레드가 아닌 이유: 정리는 살아 있는 Egress 를 하나씩 멈추므로 한 세션에 LiveKit 호출이
            // 열 건을 넘고, 건마다 최대 60초 매달릴 수 있다. 여기서 기다리면 heartbeat·강사 종료 응답이
            // 분 단위로 늘어지고, 만료 스윕(한 배치 50건)은 @Scheduled 스레드를 그만큼 붙들어 100ms 주기
            // 무음 패딩까지 굶긴다 — 풀 크기를 작업 수에 맞춰 잡아 둔 계산이 무너진다.
            //
            // 정리 실패가 종료를 되돌리지 않는 계약은 그대로다. 이미 커밋된 뒤라 되돌릴 것도 없다.
            mediaCleanupExecutor.execute(() -> releaseMediaRoom(result.sessionId()));
        }
        return result;
    }

    /** 세션 종료의 DB 상태 전이. 빠른 메모리·Redis 반납까지만 여기 두고, 외부 HTTP 는 {@link #end}가 커밋 뒤에 한다. */
    private EndSessionResult endInTransaction(EndSessionCommand command) {
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
        return new EndSessionResult(ended.id(), ended.status(), true);
    }

    /**
     * 미디어 쪽 뒷정리: 녹화를 멈추고 room 을 닫는다(LIVE-009·LIVE-010). {@link #end}의 트랜잭션이 커밋된 뒤 {@code mediaCleanupExecutor} 에서 불린다 —
     * 여기의 LiveKit 호출을 DB 트랜잭션에도, 호출 스레드에도 가두지 않는다.
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
