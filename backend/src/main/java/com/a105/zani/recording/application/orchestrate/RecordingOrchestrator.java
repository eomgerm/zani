package com.a105.zani.recording.application.orchestrate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.recording.application.exception.OrphanedTrackEgressException;
import com.a105.zani.recording.application.port.AudioStreamEgressRegistryPort;
import com.a105.zani.recording.application.port.AudioStreamEgressRequest;
import com.a105.zani.recording.application.port.NewRecordingOutboxMessage;
import com.a105.zani.recording.application.port.PendingRecordingOutboxMessage;
import com.a105.zani.recording.application.port.RecordingOutboxPort;
import com.a105.zani.recording.application.port.RecordingOutboxType;
import com.a105.zani.recording.application.port.TrackEgressPayload;
import com.a105.zani.recording.application.port.TrackEgressPort;
import com.a105.zani.recording.application.port.TrackEgressRequest;
import com.a105.zani.recording.domain.exception.ForbiddenStudentCameraTrackException;
import com.a105.zani.recording.domain.exception.InvalidRecordingTrackException;
import com.a105.zani.recording.domain.model.Recording;
import com.a105.zani.recording.domain.model.RecordingAlias;
import com.a105.zani.recording.domain.model.RecordingTrackPolicy;
import com.a105.zani.recording.domain.model.TrackRecordingDecision;
import com.a105.zani.recording.domain.model.TrackSource;
import com.a105.zani.recording.domain.repository.RecordingRepository;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * outbox 기반 Egress 시작과 중복 방지를 담당하는 녹화 orchestrator.
 *
 * <p>쓰기 경로(request)는 비즈니스 트랜잭션 안에서 outbox 행만 남기고, 외부(LiveKit) 호출은 릴레이가 outbox를 소비하며 수행한다. 중복 방지는 두 겹이다: dedup key
 * UNIQUE가 같은 작업의 중복 "등록"을, claim(PENDING→IN_PROGRESS 원자 전환)이 다중 인스턴스·재시작 시의 중복 "수행"을 막는다. 실패는 지수 백오프로 재시도하고 상한을 넘으면
 * FAILED로 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingOrchestrator implements RequestTrackEgressUseCase, RelayRecordingOutboxUseCase {

    private static final int RELAY_BATCH_SIZE = 20;
    private static final int MAX_RELAY_ATTEMPTS = 5;
    private static final int FIRST_ATTEMPT = 1;
    /** 백오프 기본 간격. attempt가 오를수록 2배씩 늘어난다(30s, 1m, 2m, 4m). */
    private static final Duration RETRY_BASE_DELAY = Duration.ofSeconds(30);
    /**
     * IN_PROGRESS로 방치된 행을 되살리는 lease 시간. 외부 호출이 아직 진행 중인데 lease가 만료되면 다른 인스턴스가 같은 트랙에 Egress를 중복 시작할 수 있으므로, LiveKit 호출
     * 상한({@code LiveKitTrackEgressAdapter.CALL_TIMEOUT} = 60초)보다 반드시 크게 잡는다. 그쪽 상한을 올릴 때는 이 값도 함께 봐야 한다.
     */
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(2);
    /** LiveKit Track SID 형식. 경로 구성에 쓰이므로 형식 밖 값은 거부한다. */
    private static final Pattern TRACK_SID_PATTERN = Pattern.compile("TR_[A-Za-z0-9_-]+");

    private final RecordingOutboxPort outboxStore;
    private final TrackEgressPort trackEgressPort;
    private final RecordingRepository recordingRepository;
    private final AudioStreamEgressRegistryPort audioStreamEgressRegistry;
    private final Clock clock;

    @Override
    @Transactional
    public RequestTrackEgressResult request(RequestTrackEgressCommand command) {
        TrackRecordingDecision decision =
                RecordingTrackPolicy.decide(command.role(), command.source(), command.studentScreenShareApproved());
        if (decision == TrackRecordingDecision.FORBIDDEN) {
            // 학생 카메라는 Egress 요청 생성 자체가 금지된다(가이드 §13). 보안 위반으로 기록하고 거부한다.
            log.warn(
                    "Forbidden student camera egress requested: session={}, trackSid={}",
                    command.sessionId(),
                    command.trackSid());
            throw new ForbiddenStudentCameraTrackException();
        }
        if (decision == TrackRecordingDecision.SKIP) {
            return new RequestTrackEgressResult(decision, false);
        }
        // alias·trackSid는 Egress 출력 파일 경로에 들어간다. 익명 별칭 형식과 SID 형식을 등록 시점에 강제해
        // 경로 탈출·실명 유입을 원천 차단한다(가이드 §18).
        RecordingAlias alias = RecordingAlias.of(command.recordingAlias());
        if (alias.isInstructor() != (command.role() == SessionParticipantRole.INSTRUCTOR)
                || command.trackSid() == null
                || !TRACK_SID_PATTERN.matcher(command.trackSid()).matches()) {
            throw new InvalidRecordingTrackException();
        }
        TrackEgressPayload payload = new TrackEgressPayload(command.trackSid(), alias.value(), command.source());
        boolean enqueued = outboxStore.enqueue(new NewRecordingOutboxMessage(
                trackDedupKey(command.sessionId(), command.trackSid()),
                RecordingOutboxType.START_TRACK_EGRESS,
                command.sessionId(),
                payload));

        // 강사 마이크만 코칭 버퍼로도 흘려보낸다. 파일 출력과 WebSocket 출력은 한 Egress 가 동시에 낼 수 없어
        // 별도 실행을 하나 더 띄운다. dedupKey 가 달라 두 작업이 공존하며, 각자 한 번씩만 시작된다.
        if (command.role() == SessionParticipantRole.INSTRUCTOR && command.source() == TrackSource.MICROPHONE) {
            outboxStore.enqueue(new NewRecordingOutboxMessage(
                    audioStreamDedupKey(command.sessionId(), command.trackSid()),
                    RecordingOutboxType.START_AUDIO_STREAM_EGRESS,
                    command.sessionId(),
                    payload));
        }
        return new RequestTrackEgressResult(decision, enqueued);
    }

    /** 트랜잭션을 걸지 않는다: 외부(LiveKit) 호출을 DB 트랜잭션 안에 가두지 않기 위한 의도적 경계다. 각 상태 전이는 store가 자체 트랜잭션으로 처리한다. */
    @Override
    public int relayPendingOutbox() {
        Instant now = clock.instant();
        outboxStore.requeueExpiredClaims(now.minus(CLAIM_LEASE), now);

        List<PendingRecordingOutboxMessage> due = outboxStore.fetchDue(RELAY_BATCH_SIZE, now);
        int processed = 0;
        for (PendingRecordingOutboxMessage message : due) {
            // claim에 성공한 릴레이만 수행한다(다중 인스턴스·중복 스케줄 실행 방어).
            if (!outboxStore.claim(message.id(), clock.instant())) {
                continue;
            }
            int attempt = message.attemptCount() + 1;
            try {
                handle(message, attempt);
                // 완료 표시는 작업의 일부가 아니다. 여기서 실패해도 handle을 다시 실행하면 안 되므로(중복 Egress)
                // 재시도 경로로 보내지 않고 별도로 처리한다. 재실행되더라도 handle이 기존 Egress를 채택해 멱등하다.
                markCompletedSafely(message);
            } catch (OrphanedTrackEgressException orphaned) {
                // 이미 Egress가 시작된 작업은 재시도하지 않는다(중복 Egress 방지). 발급된 egressId를 남겨 회수 가능하게 한다.
                outboxStore.markFailed(message.id(), "orphaned egress: " + orphaned.egressId());
                log.error(
                        "Recording outbox {} not retried to avoid duplicate egress (egressId={})",
                        message.dedupKey(),
                        orphaned.egressId());
            } catch (RuntimeException exception) {
                String error =
                        exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                if (attempt >= MAX_RELAY_ATTEMPTS) {
                    outboxStore.markFailed(message.id(), error);
                    log.error(
                            "Recording outbox {} failed permanently after {} attempts: {}",
                            message.dedupKey(),
                            attempt,
                            error);
                } else {
                    Instant nextAttemptAt = clock.instant().plus(retryDelay(attempt));
                    outboxStore.markRetry(message.id(), error, nextAttemptAt);
                    log.warn(
                            "Recording outbox {} attempt {} failed, retry at {}: {}",
                            message.dedupKey(),
                            attempt,
                            nextAttemptAt,
                            error);
                }
            }
            processed++;
        }
        return processed;
    }

    private void markCompletedSafely(PendingRecordingOutboxMessage message) {
        try {
            outboxStore.markCompleted(message.id());
        } catch (RuntimeException completionFailure) {
            // 작업 자체는 성공했다. lease 만료 후 이 행이 다시 소비되더라도 handle이 기존 Egress를 채택하므로
            // 외부 부작용은 한 번만 발생한다.
            log.error(
                    "Recording outbox {} completed but could not be marked COMPLETED: {}",
                    message.dedupKey(),
                    completionFailure.getMessage());
        }
    }

    private void handle(PendingRecordingOutboxMessage message, int attempt) {
        switch (message.type()) {
            case START_TRACK_EGRESS -> startTrackEgress(message, attempt);
            case START_AUDIO_STREAM_EGRESS -> startAudioStreamEgress(message, attempt);
        }
    }

    /**
     * 강사 오디오 실시간 전달용 Egress 를 시작한다.
     *
     * <p>파일 Egress 와 달리 recordings 행을 만들지 않는다. 이 스트림은 녹화물이 아니라 메모리 버퍼로 흘러가 전사 후 사라지므로 남길 산출물이 없다. 그래서 고아 Egress 보정 로직이
     * 없고, 시작 자체가 끝까지 실패하면 재시도 후 포기한다 — 코칭이 빠질 뿐 수업과 녹화에는 영향이 없다.
     *
     * <p><b>중복 방지</b>: 산출물이 없어도 재실행 시 기존 실행을 채택해야 한다. 같은 트랙에 스트림 Egress 가 두 개 붙으면 두 PCM 이 한 링버퍼에 뒤섞여 전사 내용이 깨지고, 누적
     * 바이트가 경과 시간을 앞질러 무음 패딩이 영구히 멈춘다(벽시계 정렬이 복구되지 않는다). 예외도 로그도 남지 않아 조용히 틀린다.
     */
    private void startAudioStreamEgress(PendingRecordingOutboxMessage message, int attempt) {
        TrackEgressPayload payload = message.payload();
        AudioStreamEgressRequest request = new AudioStreamEgressRequest(message.sessionId(), payload.trackSid());

        // 재실행(완료 표시 유실·크래시 후 lease 회수·재시도)일 수 있으므로, 첫 시도가 아니면 아직 살아 있는 스트림
        // Egress 를 먼저 찾아 채택한다. 종료된 실행은 채택하지 않는다(흐름이 끊긴 상태라 새로 시작해야 한다).
        String egressId = attempt > FIRST_ATTEMPT
                ? trackEgressPort
                        .findLiveAudioStreamEgressId(request)
                        .orElseGet(
                                () -> trackEgressPort.startAudioStream(request).egressId())
                : trackEgressPort.startAudioStream(request).egressId();

        // recordings 행이 없는 Egress 라, webhook 이 "녹화 미준비"로 오해하지 않도록 종류를 표시해 둔다.
        // 채택한 경우에도 다시 표시한다 — 표시가 TTL 로 사라졌거나 애초에 저장에 실패했을 수 있고, 저장은 멱등하다.
        audioStreamEgressRegistry.remember(egressId, message.sessionId());
        log.info(
                "Instructor audio stream egress {} started: session={}, trackSid={}, attempt={}",
                egressId,
                message.sessionId(),
                payload.trackSid(),
                attempt);
    }

    private void startTrackEgress(PendingRecordingOutboxMessage message, int attempt) {
        TrackEgressPayload payload = message.payload();
        TrackEgressRequest request = new TrackEgressRequest(
                message.sessionId(), payload.trackSid(), payload.recordingAlias(), payload.source());

        // 재실행(완료 표시 유실·크래시 후 lease 회수·재시도)일 수 있으므로, 첫 시도가 아니면 이 트랙의 기존 Egress를
        // 먼저 찾아 채택한다(이미 종료된 실행도 포함). 이렇게 하면 같은 트랙에 두 번째 Egress가 붙지 않는다.
        String egressId = attempt > FIRST_ATTEMPT
                ? trackEgressPort
                        .findExistingEgressId(request)
                        .orElseGet(() -> trackEgressPort.start(request).egressId())
                : trackEgressPort.start(request).egressId();

        // 채택한 Egress의 녹화 행이 이미 있으면(이전 실행이 저장까지 마친 경우) 다시 만들지 않는다.
        if (recordingRepository.findByLivekitEgressId(egressId).isPresent()) {
            log.info(
                    "Track egress {} already recorded, skipping duplicate row: session={}",
                    egressId,
                    message.sessionId());
            return;
        }
        try {
            recordingRepository.save(Recording.startTrack(
                    TsidGenerator.generate(), message.sessionId(), egressId, attempt, clock.instant()));
        } catch (RuntimeException persistFailure) {
            // Egress는 이미 LiveKit에서 시작됐다. 이 작업을 재시도하면 같은 트랙에 두 번째 Egress가 붙으므로
            // 발급된 egressId를 남기고 재시도 대상에서 제외한다(대조 작업이 회수).
            log.error(
                    "Track egress {} started but recording row was not persisted: session={}, trackSid={}",
                    egressId,
                    message.sessionId(),
                    payload.trackSid(),
                    persistFailure);
            throw new OrphanedTrackEgressException(egressId, persistFailure);
        }
        log.info("Track egress {} started: session={}, trackSid={}", egressId, message.sessionId(), payload.trackSid());
    }

    /** 지수 백오프: 30s, 1m, 2m, 4m. */
    private static Duration retryDelay(int attempt) {
        return RETRY_BASE_DELAY.multipliedBy(1L << Math.min(attempt - 1, 4));
    }

    private static String trackDedupKey(Long sessionId, String trackSid) {
        return "track:" + sessionId + ":" + trackSid;
    }

    /** 파일 Egress 와 다른 키라 같은 트랙에 두 작업이 공존한다. */
    private static String audioStreamDedupKey(Long sessionId, String trackSid) {
        return "audio-stream:" + sessionId + ":" + trackSid;
    }
}
