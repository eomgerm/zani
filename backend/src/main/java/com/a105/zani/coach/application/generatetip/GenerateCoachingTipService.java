package com.a105.zani.coach.application.generatetip;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.a105.zani.attention.application.port.CoachingOutcome;
import com.a105.zani.attention.application.port.CoachingTip;
import com.a105.zani.attention.application.port.CoachingTipPipelinePort;
import com.a105.zani.attention.application.port.CoachingTipRequest;
import com.a105.zani.attention.application.port.CoachingTipType;
import com.a105.zani.attention.application.port.CoachingTipUnavailableReason;
import com.a105.zani.attention.application.port.CoachingTriggerStatePort;
import com.a105.zani.audioclip.application.captureclip.CaptureAudioClipCommand;
import com.a105.zani.audioclip.application.captureclip.CaptureAudioClipResult;
import com.a105.zani.audioclip.application.captureclip.CaptureAudioClipUseCase;
import com.a105.zani.coach.application.port.TipConcept;
import com.a105.zani.coach.application.port.TipConceptPort;
import com.a105.zani.coach.application.port.TipConceptRequest;
import com.a105.zani.coach.application.storehistory.CoachingHistory;
import com.a105.zani.coach.application.storehistory.CoachingResponseCounts;
import com.a105.zani.coach.application.storehistory.CoachingTranscript;
import com.a105.zani.coach.application.storehistory.StoreCoachingHistoryUseCase;
import com.a105.zani.coach.domain.model.CoachingTipRatios;
import com.a105.zani.coach.infrastructure.config.CoachPipelineProperties;
import com.a105.zani.coach.infrastructure.config.CoachTipProperties;
import com.a105.zani.session.application.getcoachingcontext.GetSessionCoachingContextQuery;
import com.a105.zani.session.application.getcoachingcontext.GetSessionCoachingContextResult;
import com.a105.zani.session.application.getcoachingcontext.GetSessionCoachingContextUseCase;

/**
 * 트리거가 열린 뒤 팁을 만들어 결과를 돌려놓는다. (S15P11A105-204)
 *
 * <p>기술 어댑터가 아니라 여러 application 계약(세션 조회·오디오 캡처·개념 추출·트리거 상태)을 엮는 오케스트레이션이라 application 계층에 둔다.
 *
 * <p>유형을 먼저 고르는 이유: 무응답·자리비움 팁은 §8 문구에 자리표시자가 없어 전사도 GMS 도 필요 없다. 전사를 먼저 하면 이 두 유형에서 whisper 크레딧을 쓰고, executor 포화나 GMS
 * 장애에 고정 문구 팁까지 끌려간다.
 *
 * <p>결과가 늦게 도착해 쿨타임이 이미 끝났으면 {@code CoachingTriggerStatePort} 가 쓰지 않고 버린다. 10분 지난 팁을 새 팁으로 되살리면 안 된다.
 */
@Service
public class GenerateCoachingTipService implements CoachingTipPipelinePort {

    private static final Logger log = LoggerFactory.getLogger(GenerateCoachingTipService.class);

    private final Executor executor;
    private final Clock clock;
    private final GetSessionCoachingContextUseCase getSessionCoachingContextUseCase;
    private final CaptureAudioClipUseCase captureAudioClipUseCase;
    private final TipConceptPort tipConceptPort;
    private final CoachingTriggerStatePort coachingTriggerStatePort;
    private final StoreCoachingHistoryUseCase storeCoachingHistoryUseCase;
    private final double minConfidence;
    private final int transcriptTailChars;
    private final Duration maxTriggerDelay;

    public GenerateCoachingTipService(
            @Qualifier("coachingTipExecutor") Executor executor,
            Clock clock,
            GetSessionCoachingContextUseCase getSessionCoachingContextUseCase,
            CaptureAudioClipUseCase captureAudioClipUseCase,
            TipConceptPort tipConceptPort,
            CoachingTriggerStatePort coachingTriggerStatePort,
            StoreCoachingHistoryUseCase storeCoachingHistoryUseCase,
            CoachTipProperties tipProperties,
            CoachPipelineProperties pipelineProperties) {
        this.executor = executor;
        this.clock = clock;
        this.getSessionCoachingContextUseCase = getSessionCoachingContextUseCase;
        this.captureAudioClipUseCase = captureAudioClipUseCase;
        this.tipConceptPort = tipConceptPort;
        this.coachingTriggerStatePort = coachingTriggerStatePort;
        this.storeCoachingHistoryUseCase = storeCoachingHistoryUseCase;
        this.minConfidence = tipProperties.minConfidence();
        this.transcriptTailChars = tipProperties.transcriptTailChars();
        this.maxTriggerDelay = pipelineProperties.maxTriggerDelay();
    }

    @Override
    public void start(CoachingTipRequest request) {
        CoachingTipRatios ratios = ratiosOf(request);
        Optional<CoachingTipType> selected = CoachingTipTypeSelector.select(ratios);
        if (selected.isEmpty()) {
            // 트리거는 비율이 임계를 넘어야 열리므로 정상 흐름에서는 오지 않는다. 집계와 트리거 판정이 어긋난 신호다.
            log.warn(
                    "팁 유형을 고를 수 없어 트리거를 비웁니다. sessionId={}, triggerId={}, stage=SELECT",
                    request.sessionId(),
                    request.triggerId());
            completeFailure(
                    request,
                    null,
                    CoachingTipUnavailableReason.TIP_GENERATION_FAILED,
                    CoachingTranscript.notAttempted(),
                    "SELECT",
                    0,
                    0,
                    null);
            return;
        }

        CoachingTipType tipType = selected.get();
        if (!CoachingTipComposer.requiresConcept(tipType)) {
            // 고정 문구는 executor 를 거치지 않는다. 큐 포화·GMS 장애와 무관하게 떠야 한다.
            completeSuccess(
                    request,
                    CoachingTipComposer.compose(tipType, ratios, null),
                    CoachingTranscript.skippedNotRequired(),
                    "FIXED",
                    0,
                    0);
            return;
        }

        try {
            executor.execute(() -> generate(request, ratios, tipType));
        } catch (RejectedExecutionException exception) {
            // 큐가 찼다. pending 으로 두면 강사가 쿨타임 10분 내내 생성 중만 본다.
            log.warn(
                    "팁 생성 큐가 차서 건너뜁니다. sessionId={}, triggerId={}, stage=QUEUE, result={}",
                    request.sessionId(),
                    request.triggerId(),
                    CoachingTipUnavailableReason.TIP_GENERATION_FAILED);
            completeFailure(
                    request,
                    tipType,
                    CoachingTipUnavailableReason.TIP_GENERATION_FAILED,
                    CoachingTranscript.notAttempted(),
                    "QUEUE",
                    0,
                    0,
                    exception);
        }
    }

    private void generate(CoachingTipRequest request, CoachingTipRatios ratios, CoachingTipType tipType) {
        long startedAt = System.nanoTime();
        long triggerDelayMs = triggerDelayMs(request);

        if (triggerDelayMs > maxTriggerDelay.toMillis()) {
            // 지금 오디오를 떠도 트리거 당시가 아닌 발화가 섞인다. 근거가 어긋난 팁보다 생략이 낫다.
            log.warn(
                    "트리거 지연이 상한을 넘어 전사·팁 생성을 건너뜁니다."
                            + " sessionId={}, triggerId={}, stage=QUEUE, result={}, triggerDelayMs={}",
                    request.sessionId(),
                    request.triggerId(),
                    CoachingTipUnavailableReason.TIP_GENERATION_FAILED,
                    triggerDelayMs);
            completeFailure(
                    request,
                    tipType,
                    CoachingTipUnavailableReason.TIP_GENERATION_FAILED,
                    CoachingTranscript.notAttempted(),
                    "QUEUE",
                    triggerDelayMs,
                    elapsedMs(startedAt),
                    null);
            return;
        }

        GetSessionCoachingContextResult context;
        try {
            context = getSessionCoachingContextUseCase.get(new GetSessionCoachingContextQuery(request.sessionId()));
        } catch (RuntimeException exception) {
            // 세션 조회 실패는 전사 실패가 아니다. 같은 사유로 묶으면 로그에서 "GMS 가 느리다" 로 읽혀 엉뚱한 곳을 본다.
            completeFailure(
                    request,
                    tipType,
                    CoachingTipUnavailableReason.TIP_GENERATION_FAILED,
                    CoachingTranscript.notAttempted(),
                    "CONTEXT",
                    triggerDelayMs,
                    elapsedMs(startedAt),
                    exception);
            return;
        }

        CaptureAudioClipResult captured;
        try {
            captured = captureAudioClipUseCase.capture(new CaptureAudioClipCommand(request.sessionId()));
        } catch (RuntimeException exception) {
            // AudioClipTranscriptionFailedException 과 버퍼 조회 실패가 여기로 온다.
            completeFailure(
                    request,
                    tipType,
                    CoachingTipUnavailableReason.TRANSCRIPTION_FAILED,
                    CoachingTranscript.failed(),
                    "TRANSCRIBE",
                    triggerDelayMs,
                    elapsedMs(startedAt),
                    exception);
            return;
        }

        try {
            if (!captured.transcribed()
                    || captured.transcript() == null
                    || captured.transcript().isBlank()) {
                completeFailure(
                        request,
                        tipType,
                        CoachingTipUnavailableReason.NO_TRANSCRIPT,
                        CoachingTranscript.noTranscript(captured.fromEpochMs(), captured.toEpochMs()),
                        "TRANSCRIBE",
                        triggerDelayMs,
                        elapsedMs(startedAt),
                        null);
                return;
            }

            Optional<TipConcept> concept = tipConceptPort.extract(new TipConceptRequest(
                    tipType, tailOf(captured.transcript()), context.title(), elapsedMinutes(request, context)));
            if (concept.isEmpty()) {
                completeFailure(
                        request,
                        tipType,
                        CoachingTipUnavailableReason.TIP_GENERATION_FAILED,
                        transcribed(captured),
                        "EXTRACT",
                        triggerDelayMs,
                        elapsedMs(startedAt),
                        null);
                return;
            }
            if (!concept.get().isUsable() || concept.get().confidence() < minConfidence) {
                completeFailure(
                        request,
                        tipType,
                        CoachingTipUnavailableReason.LOW_CONFIDENCE,
                        transcribed(captured),
                        "EXTRACT",
                        triggerDelayMs,
                        elapsedMs(startedAt),
                        concept.get().concept(),
                        null);
                return;
            }

            CoachingTip tip =
                    CoachingTipComposer.compose(tipType, ratios, concept.get().concept());
            completeSuccess(request, tip, transcribed(captured), "COMPOSE", triggerDelayMs, elapsedMs(startedAt));
        } catch (RuntimeException exception) {
            completeFailure(
                    request,
                    tipType,
                    CoachingTipUnavailableReason.TIP_GENERATION_FAILED,
                    transcribed(captured),
                    "UNEXPECTED",
                    triggerDelayMs,
                    elapsedMs(startedAt),
                    exception);
        }
    }

    /** 85 가 비율을 double 4개로 펼쳐 넘기므로 도메인 계산이 쓰는 형태로 옮겨 담는다. */
    private CoachingTipRatios ratiosOf(CoachingTipRequest request) {
        return new CoachingTipRatios(
                request.studentsCounted(),
                request.significantRatio(),
                request.confusedRatio(),
                request.missedRatio(),
                request.nonResponseRatio(),
                request.unmeasurableRatio());
    }

    /**
     * 트리거 시각부터 지금까지의 지연. 폴링에서 executor 제출까지의 시간도 포함한다.
     *
     * <p>서버 시계가 되돌아간 경우를 대비해 음수는 0 으로 본다.
     */
    private long triggerDelayMs(CoachingTipRequest request) {
        long delay = Duration.between(request.triggeredAt(), clock.instant()).toMillis();
        return Math.max(delay, 0);
    }

    /** 수업 경과 분은 작업 실행 시각이 아니라 트리거 시각으로 잰다. 큐에서 밀려도 프롬프트의 경과가 달라지지 않는다. */
    private long elapsedMinutes(CoachingTipRequest request, GetSessionCoachingContextResult context) {
        long minutes =
                Duration.between(context.startedAt(), request.triggeredAt()).toMinutes();
        return Math.max(minutes, 0);
    }

    private String tailOf(String transcript) {
        return transcript.length() <= transcriptTailChars
                ? transcript
                : transcript.substring(transcript.length() - transcriptTailChars);
    }

    private void completeSuccess(
            CoachingTipRequest request,
            CoachingTip tip,
            CoachingTranscript transcript,
            String stage,
            long triggerDelayMs,
            long elapsedMs) {
        // 문구·개념은 남기지 않는다. 강사 발화에서 온 값이라 로그에 쌓을 이유가 없다.
        log.info(
                "팁을 생성했습니다. sessionId={}, triggerId={}, stage={}, result=SUCCESS, tipType={},"
                        + " triggerDelayMs={}, elapsedMs={}",
                request.sessionId(),
                request.triggerId(),
                stage,
                tip.tipType(),
                triggerDelayMs,
                elapsedMs);
        store(
                request,
                CoachingOutcome.completed(request.triggerId(), tip),
                tip.tipType(),
                transcript,
                tip.targetConcept());
    }

    private void completeFailure(
            CoachingTipRequest request,
            CoachingTipType selectedTipType,
            CoachingTipUnavailableReason reason,
            CoachingTranscript transcript,
            String stage,
            long triggerDelayMs,
            long elapsedMs,
            RuntimeException cause) {
        completeFailure(request, selectedTipType, reason, transcript, stage, triggerDelayMs, elapsedMs, null, cause);
    }

    private void completeFailure(
            CoachingTipRequest request,
            CoachingTipType selectedTipType,
            CoachingTipUnavailableReason reason,
            CoachingTranscript transcript,
            String stage,
            long triggerDelayMs,
            long elapsedMs,
            String topic,
            RuntimeException cause) {
        String message =
                "팁을 만들지 못했습니다. sessionId={}, triggerId={}, stage={}, result={}," + " triggerDelayMs={}, elapsedMs={}";
        if (cause == null) {
            log.info(message, request.sessionId(), request.triggerId(), stage, reason, triggerDelayMs, elapsedMs);
        } else {
            log.warn(
                    message, request.sessionId(), request.triggerId(), stage, reason, triggerDelayMs, elapsedMs, cause);
        }
        store(request, CoachingOutcome.unavailable(request.triggerId(), reason), selectedTipType, transcript, topic);
    }

    /**
     * 결과를 트리거 상태에 돌려놓는다.
     *
     * <p>비동기 작업이라 되돌릴 경로가 없다. Redis 장애로 실패하면 로그만 남기고 끝낸다 — 여기서 다시 시도하면 늦은 팁이 되살아나거나 강사 폴링이 막힌다.
     */
    private void store(
            CoachingTipRequest request,
            CoachingOutcome outcome,
            CoachingTipType selectedTipType,
            CoachingTranscript transcript,
            String topic) {
        try {
            storeCoachingHistoryUseCase.store(new CoachingHistory(
                    request.sessionId(),
                    request.triggerId(),
                    request.triggeredAt(),
                    clock.instant(),
                    new CoachingResponseCounts(
                            request.studentsCounted(),
                            request.significantCount(),
                            request.confusedCount(),
                            request.missedCount(),
                            request.nonResponseCount(),
                            request.unmeasurableCount()),
                    selectedTipType,
                    transcript,
                    topic,
                    outcome.tip(),
                    outcome.unavailableReason()));
        } catch (RuntimeException exception) {
            log.warn(
                    "Coaching history could not be stored. sessionId={}, triggerId={}, stage=HISTORY_STORE",
                    request.sessionId(),
                    request.triggerId(),
                    exception);
        }

        try {
            coachingTriggerStatePort.completeOutcome(request.sessionId(), outcome);
        } catch (RuntimeException exception) {
            log.warn(
                    "팁 결과를 보관하지 못했습니다. sessionId={}, triggerId={}, stage=STORE",
                    request.sessionId(),
                    request.triggerId(),
                    exception);
        }
    }

    private CoachingTranscript transcribed(CaptureAudioClipResult captured) {
        return CoachingTranscript.transcribed(captured.fromEpochMs(), captured.toEpochMs());
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
