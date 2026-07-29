package com.a105.zani.attention.application.collect;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.attention.application.exception.InvalidDetectionTimelineException;
import com.a105.zani.attention.application.exception.NotSessionStudentException;
import com.a105.zani.attention.application.exception.UnsupportedDetectorContractException;
import com.a105.zani.attention.application.port.AttentionSnapshot;
import com.a105.zani.attention.application.port.AttentionStatePort;
import com.a105.zani.attention.application.port.ObservationApplied;
import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.DetectionRecord;
import com.a105.zani.attention.domain.model.DetectionRunCounters;
import com.a105.zani.attention.domain.model.DetectionRunTransition;
import com.a105.zani.attention.domain.model.DetectionSignal;
import com.a105.zani.attention.domain.model.DetectorOutcome;
import com.a105.zani.attention.domain.repository.DetectionRecordRepository;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantQuery;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantResult;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * 브라우저 검출기가 10초마다 보낸 관측을 받아, 기록으로 남기고 학생 상태를 파생한다.
 *
 * <p>서버가 받는 것은 <b>관측(검출기 출력 7종)</b>이고, 집계에 쓰는 <b>학생 상태 6종</b>은 서버가 만든다(확정 문서 §1·§2). 저참여와 {@code UNMEASURABLE} 은 3연속이어야
 * 확정되므로 연속 카운터를 서버가 들고 있는다(§4.1).
 *
 * <p>DB 쓰기 하나뿐이라 이 경로를 트랜잭션으로 감싸지 않는다. 감싸면 잦은 관측이 Redis 왕복 동안 DB 커넥션을 붙든다. presence heartbeat 가 같은 이유로 트랜잭션을 쓰지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectAttentionEventService implements CollectAttentionEventUseCase {

    /** 현재 상태 키의 TTL. presence TTL 과 같은 값이라 분자와 분모가 같은 생존 구간을 갖는다(§6.2). */
    private static final Duration CURRENT_STATE_TTL = Duration.ofSeconds(30);

    /** 분자에 남는 기간(§7.3). 프롬프트 쿨타임과 같아 "띄울 만한 상태였다"가 곧 "분자에 있다"가 된다. */
    private static final Duration SIGNIFICANT_WINDOW = Duration.ofMinutes(5);

    /**
     * 집계 진행 상태의 보관 기간. 연속 카운터·반영 지점·측정 불가 구간·분모 제외 표시가 모두 이 값을 쓴다.
     *
     * <p>판정이 10초마다 오므로 몇 주기를 건너뛰어도 이어지도록 넉넉히 잡는다. 짧게 잡으면 네트워크가 잠깐 흔들린 학생의 카운터가 0으로 돌아가 프롬프트가 늦어지고, 길게 잡으면 한참 뒤 재입장한 학생이
     * 옛 값을 물려받는다. 학생이 아예 사라지면 presence 가 분모에서 빼 주므로 제외 표시를 오래 붙들 필요도 없다.
     */
    private static final Duration AGGREGATION_STATE_TTL = Duration.ofMinutes(2);

    /** 멱등 판정에 쓰는 clientEventId 기록 보관 기간. */
    private static final Duration EVENT_ID_TTL = Duration.ofMinutes(10);

    /**
     * 측정 불가가 이만큼 이어지면 집단 비율 분모에서 뺀다(§7.1).
     *
     * <p>빼는 데 1분이 걸리고 넣는 데는 즉시인 비대칭이 각각 맞다. 카메라를 켜는 순간 관측이 가능해지므로 기다릴 이유가 없고, 껐다 켰다를 반복해도 빼는 데 1분이 걸려 분모가 출렁이지 않는다.
     */
    private static final Duration SUSTAINED_OUTAGE = Duration.ofMinutes(1);

    /** 클라이언트 시계가 조금 빠른 것은 받아 준다. 이보다 미래면 시간선이 깨진 것으로 본다. */
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(1);

    private final ResolveSessionParticipantUseCase resolveSessionParticipantUseCase;
    private final DetectionRecordRepository detectionRecordRepository;
    private final AttentionStatePort attentionStatePort;
    private final DetectorContractProperties detectorContract;
    private final Clock clock;

    @Override
    public CollectAttentionEventResult collect(CollectAttentionEventCommand command) {
        ResolveSessionParticipantResult participant = resolveSessionParticipantUseCase.resolve(
                new ResolveSessionParticipantQuery(command.sessionId(), command.userId()));

        // 판정은 학생만 만든다(§2). 강사 관측이 섞이면 분자에만 끼어 코칭 비율이 부풀어 오른다.
        if (participant.role() != SessionParticipantRole.STUDENT) {
            throw new NotSessionStudentException();
        }
        validateContract(command);
        validateTimeline(participant.sessionStartedAt(), command);

        long sessionId = command.sessionId();
        long participantId = participant.participantId();
        long observedOffsetMs = offsetMs(participant.sessionStartedAt(), command.observedAt());

        if (!attentionStatePort.registerEvent(sessionId, participantId, command.clientEventId(), EVENT_ID_TTL)) {
            log.debug("이미 처리한 판정입니다. sessionId={}, clientEventId={}", sessionId, command.clientEventId());
            return CollectAttentionEventResult.alreadyRecorded();
        }

        try {
            // 기록이 먼저다. 집계는 30초면 사라지지만 이 행은 수업 후 리포트의 근거로 남는다.
            boolean stored = detectionRecordRepository.saveIfNew(
                    record(sessionId, participantId, command, participant, observedOffsetMs));
            if (!stored) {
                // 행은 있는데 멱등 표시는 없다 — 앞선 시도가 저장까지 마치고 집계 반영에서 죽었다는 뜻이다.
                // 여기서 되돌아가면 그 관측의 집계 반영이 영영 되살아나지 못한다. 반영만 다시 한다.
                log.debug(
                        "기록은 남아 있어 집계 반영만 다시 합니다. sessionId={}, clientEventId={}", sessionId, command.clientEventId());
            }

            // 늦게 도착한 옛 관측이 최신 상태를 덮어쓰면 학생이 과거로 되돌아간다. 기록만 남기고 집계는 건드리지 않는다.
            if (!applyToCoachingState(sessionId, participantId, command.signal(), observedOffsetMs)) {
                log.debug("더 최신 관측이 이미 반영됐습니다. sessionId={}, offsetMs={}", sessionId, observedOffsetMs);
                return CollectAttentionEventResult.recordedButSuperseded();
            }
        } catch (RuntimeException exception) {
            // 멱등 표시만 남으면 재시도가 "이미 처리했다"는 거짓 성공을 받고 그 관측이 영영 사라진다.
            clearMarkerQuietly(sessionId, participantId, command.clientEventId());
            throw exception;
        }
        return CollectAttentionEventResult.recorded();
    }

    /**
     * 관측을 학생 상태로 바꿔 집계에 반영한다.
     *
     * <p>3·4단계와 {@code CAMERA_OFF} 는 그 자리에서 확정되고, 저참여와 {@code UNMEASURABLE} 은 3연속이어야 한다. 저참여 3연속은 상태를 바로 정하지 않는다 — 이해
     * 확인 응답이 와야 CONFUSED·MISSED·NON_RESPONSE 중 무엇인지 갈린다(§2).
     *
     * <p>순서 판단·카운터·반영 지점·측정 불가 구간은 저장소가 한 덩어리로 처리한다. 뒤이은 상태 기록이 실패하면 이 관측의 상태 확정은 잃지만, 10초 뒤 다음 관측이 곧바로 다시 확정한다. 카운터를 두
     * 번 올리는 쪽은 그렇게 저절로 낫지 않는다 — 3연속이 관측 두 건으로 앞당겨진 채 남는다.
     *
     * @return 반영했으면 {@code true}, 더 최신 판정이 이미 반영돼 있어 건드리지 않았으면 {@code false}
     */
    private boolean applyToCoachingState(
            long sessionId, long participantId, DetectionSignal signal, long observedOffsetMs) {
        Optional<ObservationApplied> result = attentionStatePort.applyObservation(
                sessionId,
                participantId,
                DetectionRunTransition.of(signal),
                signal.outcome().suspendsMeasurement(),
                observedOffsetMs,
                AGGREGATION_STATE_TTL);
        if (result.isEmpty()) {
            return false;
        }
        ObservationApplied applied = result.get();
        applyDenominatorMembership(sessionId, participantId, applied.measurementOutageMs());

        Optional<AttentionState> confirmed = signal.outcome() == DetectorOutcome.UNMEASURABLE
                ? unmeasurableStateWhenRunComplete(applied.counters())
                : signal.outcome().immediateState();

        confirmed.ifPresent(state -> {
            attentionStatePort.recordCurrentState(
                    sessionId,
                    participantId,
                    new AttentionSnapshot(state, observedSignalQuality(state), clock.instant()),
                    CURRENT_STATE_TTL);
            if (state.isSignificant()) {
                attentionStatePort.markSignificant(sessionId, participantId, state, SIGNIFICANT_WINDOW);
            }
        });
        return true;
    }

    /**
     * 집단 비율 분모에 넣을지 뺄지를 정한다(§7.1).
     *
     * <p>판단은 서버가 한다. 클라이언트가 "저를 빼주세요"라고 말하는 구조는 학생 입장에서 빠지는 게 늘 유리해져 분모가 계속 줄어든다. 그래서 학생의 카메라 안내 응답이 아니라 검출기 이벤트가 근거다.
     */
    private void applyDenominatorMembership(long sessionId, long participantId, OptionalLong measurementOutageMs) {
        if (measurementOutageMs.isEmpty()) {
            // 측정이 가능해진 순간 되돌린다. 표시가 없으면 지우는 것도 아무 일이 아니라 상태를 따로 읽지 않는다.
            attentionStatePort.includeInDenominator(sessionId, participantId);
            return;
        }
        if (measurementOutageMs.getAsLong() >= SUSTAINED_OUTAGE.toMillis()) {
            attentionStatePort.excludeFromDenominator(sessionId, participantId, AGGREGATION_STATE_TTL);
        }
    }

    /**
     * {@code UNMEASURABLE} 은 3연속일 때만 학생 상태가 된다(§7.3).
     *
     * <p>고개를 크게 돌리거나 자세를 고쳐 앉는 것만으로도 그 10초의 검출률이 70%에 못 미친다. 한 건으로 분자에 넣으면 잠깐 몸을 움직인 학생이 이탈자로 계산된다.
     */
    private Optional<AttentionState> unmeasurableStateWhenRunComplete(DetectionRunCounters counters) {
        return counters.unmeasurableRunComplete() ? Optional.of(AttentionState.UNMEASURABLE) : Optional.empty();
    }

    /** 확정된 상태의 신호 품질. 관측이 아예 없는 상태는 0 이다. */
    private double observedSignalQuality(AttentionState state) {
        return state == AttentionState.GOOD ? 1.0d : 0.0d;
    }

    private void clearMarkerQuietly(long sessionId, long participantId, String clientEventId) {
        try {
            attentionStatePort.clearEvent(sessionId, participantId, clientEventId);
        } catch (RuntimeException exception) {
            log.warn("멱등 표시를 되돌리지 못했습니다. TTL 로 사라집니다. sessionId={}", sessionId, exception);
        }
    }

    private DetectionRecord record(
            long sessionId,
            long participantId,
            CollectAttentionEventCommand command,
            ResolveSessionParticipantResult participant,
            long observedOffsetMs) {
        Long windowStartedOffsetMs = command.windowStartedAt() == null
                ? null
                : offsetMs(participant.sessionStartedAt(), command.windowStartedAt());
        return new DetectionRecord(
                sessionId,
                participantId,
                command.signal(),
                observedOffsetMs,
                windowStartedOffsetMs,
                command.signalQuality(),
                command.featureSchemaVersion(),
                command.engineVersion(),
                command.clientEventId());
    }

    /**
     * 서버가 해석할 수 있는 검출기 계약인지 본다.
     *
     * <p>다른 특징 추출 계약이나 다른 모델이 낸 판정은 같은 기준으로 읽을 수 없다. 그대로 받으면 서로 다른 잣대로 잰 값이 한 집계에 섞인다.
     */
    private void validateContract(CollectAttentionEventCommand command) {
        if (!detectorContract.acceptsFeatureSchema(command.featureSchemaVersion())
                || !detectorContract.acceptsEngine(command.engineVersion())) {
            // 구버전 FE 한 반이면 분당 수백 줄이 된다. 잘못된 요청은 다른 4xx 와 같이 debug 로 남기고 400 응답 자체를 신호로 쓴다.
            log.debug(
                    "지원하지 않는 검출기 계약입니다. featureSchemaVersion={}, engineVersion={}",
                    command.featureSchemaVersion(),
                    command.engineVersion());
            throw new UnsupportedDetectorContractException();
        }
    }

    /**
     * 관측 시각이 수업 시간선과 맞는지 본다.
     *
     * <p>어긋난 값을 눌러 담으면 서로 다른 관측이 같은 오프셋을 갖게 되고 순서 판단이 무너진다. 미래 쪽도 막는다 — 시계가 크게 빠른 브라우저는 늘 "가장 최신"이 되어 이후 정상 관측을 전부
     * 밀어낸다.
     */
    private void validateTimeline(Instant sessionStartedAt, CollectAttentionEventCommand command) {
        Instant futureLimit = clock.instant().plus(FUTURE_TOLERANCE);
        boolean windowBroken = command.windowStartedAt() != null
                && (command.windowStartedAt().isBefore(sessionStartedAt)
                        || command.observedAt().isBefore(command.windowStartedAt()));
        if (command.observedAt().isBefore(sessionStartedAt)
                || command.observedAt().isAfter(futureLimit)
                || windowBroken) {
            throw new InvalidDetectionTimelineException();
        }
    }

    private long offsetMs(Instant sessionStartedAt, Instant at) {
        return Duration.between(sessionStartedAt, at).toMillis();
    }
}
