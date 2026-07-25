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
import com.a105.zani.recording.application.port.NewRecordingOutboxMessage;
import com.a105.zani.recording.application.port.PendingRecordingOutboxMessage;
import com.a105.zani.recording.application.port.RecordingOutboxStore;
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
    /** 백오프 기본 간격. attempt가 오를수록 2배씩 늘어난다(30s, 1m, 2m, 4m). */
    private static final Duration RETRY_BASE_DELAY = Duration.ofSeconds(30);
    /** IN_PROGRESS로 방치된 행을 되살리는 lease 시간. */
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(2);
    /** LiveKit Track SID 형식. 경로 구성에 쓰이므로 형식 밖 값은 거부한다. */
    private static final Pattern TRACK_SID_PATTERN = Pattern.compile("TR_[A-Za-z0-9_-]+");

    private final RecordingOutboxStore outboxStore;
    private final TrackEgressPort trackEgressPort;
    private final RecordingRepository recordingRepository;
    private final Clock clock;

    @Override
    @Transactional
    public TrackEgressRequestResult request(RequestTrackEgressCommand command) {
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
            return new TrackEgressRequestResult(decision, false);
        }
        // alias·trackSid는 Egress 출력 파일 경로에 들어간다. 익명 별칭 형식과 SID 형식을 등록 시점에 강제해
        // 경로 탈출·실명 유입을 원천 차단한다(가이드 §18).
        RecordingAlias alias = RecordingAlias.of(command.recordingAlias());
        if (alias.isInstructor() != (command.role() == SessionParticipantRole.INSTRUCTOR)
                || command.trackSid() == null
                || !TRACK_SID_PATTERN.matcher(command.trackSid()).matches()) {
            throw new InvalidRecordingTrackException();
        }
        boolean enqueued = outboxStore.enqueue(new NewRecordingOutboxMessage(
                trackDedupKey(command.sessionId(), command.trackSid()),
                RecordingOutboxType.START_TRACK_EGRESS,
                command.sessionId(),
                new TrackEgressPayload(command.trackSid(), alias.value(), command.source())));
        return new TrackEgressRequestResult(decision, enqueued);
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
                outboxStore.markCompleted(message.id());
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

    private void handle(PendingRecordingOutboxMessage message, int attempt) {
        switch (message.type()) {
            case START_TRACK_EGRESS -> startTrackEgress(message, attempt);
        }
    }

    private void startTrackEgress(PendingRecordingOutboxMessage message, int attempt) {
        TrackEgressPayload payload = message.payload();
        String egressId = trackEgressPort
                .start(new TrackEgressRequest(
                        message.sessionId(), payload.trackSid(), payload.recordingAlias(), payload.source()))
                .egressId();
        recordingRepository.save(Recording.startTrack(
                TsidGenerator.generate(), message.sessionId(), egressId, attempt, clock.instant()));
        log.info("Track egress {} started: session={}, trackSid={}", egressId, message.sessionId(), payload.trackSid());
    }

    /** 지수 백오프: 30s, 1m, 2m, 4m. */
    private static Duration retryDelay(int attempt) {
        return RETRY_BASE_DELAY.multipliedBy(1L << Math.min(attempt - 1, 4));
    }

    private static String trackDedupKey(Long sessionId, String trackSid) {
        return "track:" + sessionId + ":" + trackSid;
    }
}
