package com.a105.zani.coach.application.generatetip;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.application.port.CoachingOutcome;
import com.a105.zani.attention.application.port.CoachingTipRequest;
import com.a105.zani.attention.application.port.CoachingTipType;
import com.a105.zani.attention.application.port.CoachingTipUnavailableReason;
import com.a105.zani.attention.application.port.CoachingTriggerStatePort;
import com.a105.zani.attention.application.port.PreviousCoachingTip;
import com.a105.zani.audioclip.application.captureclip.CaptureAudioClipCommand;
import com.a105.zani.audioclip.application.captureclip.CaptureAudioClipResult;
import com.a105.zani.audioclip.application.captureclip.CaptureAudioClipUseCase;
import com.a105.zani.audioclip.application.exception.AudioClipTranscriptionFailedException;
import com.a105.zani.coach.application.port.TipConcept;
import com.a105.zani.coach.application.port.TipConceptPort;
import com.a105.zani.coach.application.port.TipConceptRequest;
import com.a105.zani.coach.application.storehistory.CoachingHistory;
import com.a105.zani.coach.application.storehistory.CoachingTranscriptStatus;
import com.a105.zani.coach.infrastructure.config.CoachPipelineProperties;
import com.a105.zani.coach.infrastructure.config.CoachTipProperties;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.getcoachingcontext.GetSessionCoachingContextResult;
import com.a105.zani.session.application.getcoachingcontext.GetSessionCoachingContextUseCase;

import static org.assertj.core.api.Assertions.assertThat;

/** 팁 생성 오케스트레이션. 어떤 사유로 끝나는지와 어느 단계를 건너뛰는지를 고정한다. */
class GenerateCoachingTipServiceTest {

    private static final long SESSION_ID = 7L;
    private static final Instant STARTED_AT = Instant.parse("2026-07-30T01:00:00Z");
    private static final Instant TRIGGERED_AT = Instant.parse("2026-07-30T01:42:00Z");

    /** 같은 스레드에서 바로 실행한다. 비동기 경계가 아니라 흐름을 검증하는 테스트다. */
    private static final Executor DIRECT = Runnable::run;

    private final List<CoachingOutcome> stored = new ArrayList<>();
    private final List<CoachingHistory> histories = new ArrayList<>();
    private final List<TipConceptRequest> extracted = new ArrayList<>();
    private final List<CaptureAudioClipCommand> captured = new ArrayList<>();

    private CoachingTriggerStatePort statePort() {
        return new CoachingTriggerStatePort() {
            @Override
            public boolean openTrigger(long sessionId, String triggerId, Duration cooldown) {
                return true;
            }

            @Override
            public Optional<CoachingOutcome> openOutcome(long sessionId) {
                return Optional.empty();
            }

            @Override
            public void completeOutcome(long sessionId, CoachingOutcome outcome) {
                stored.add(outcome);
            }

            @Override
            public Optional<PreviousCoachingTip> previousTip(long sessionId) {
                return Optional.empty();
            }
        };
    }

    private GetSessionCoachingContextUseCase sessionContext() {
        return query -> new GetSessionCoachingContextResult("자료구조와 알고리즘", STARTED_AT);
    }

    private GenerateCoachingTipService service(
            Executor executor, CaptureAudioClipUseCase capture, TipConceptPort conceptPort, Duration maxTriggerDelay) {
        return new GenerateCoachingTipService(
                executor,
                Clock.fixed(TRIGGERED_AT.plusSeconds(1), ZoneOffset.UTC),
                sessionContext(),
                capture,
                conceptPort,
                statePort(),
                histories::add,
                new CoachTipProperties(0.5, 100, 3000),
                new CoachPipelineProperties(2, 6, maxTriggerDelay));
    }

    private GenerateCoachingTipService service(CaptureAudioClipUseCase capture, TipConceptPort conceptPort) {
        return service(DIRECT, capture, conceptPort, Duration.ofSeconds(10));
    }

    private CaptureAudioClipUseCase transcribing(String transcript) {
        return command -> {
            captured.add(command);
            return new CaptureAudioClipResult(true, transcript, 300_000, 1_000_000L, 1_300_000L);
        };
    }

    private TipConceptPort concept(TipConcept value) {
        return request -> {
            extracted.add(request);
            return Optional.ofNullable(value);
        };
    }

    /** 개념이 필요 없는 유형: 무응답이 지배적. */
    private CoachingTipRequest fixedTipRequest() {
        return new CoachingTipRequest(SESSION_ID, "trigger-1", TRIGGERED_AT, 10, 4, 0, 0, 4, 0, null);
    }

    /** 개념이 필요한 유형: 헷갈림이 지배적. */
    private CoachingTipRequest conceptTipRequest() {
        return new CoachingTipRequest(SESSION_ID, "trigger-2", TRIGGERED_AT, 10, 3, 3, 0, 0, 0, null);
    }

    private CoachingOutcome onlyStored() {
        assertThat(stored).hasSize(1);
        return stored.getFirst();
    }

    @Test
    @DisplayName("무응답·자리비움 팁은 전사도 GMS 도 부르지 않고 즉시 완성한다")
    void completesFixedTipWithoutTranscriptionOrGms() {
        GenerateCoachingTipService service = service(
                command -> {
                    throw new AssertionError("고정 문구 팁은 전사를 부르지 않아야 한다");
                },
                request -> {
                    throw new AssertionError("고정 문구 팁은 GMS 를 부르지 않아야 한다");
                });

        service.start(fixedTipRequest());

        CoachingOutcome outcome = onlyStored();
        assertThat(outcome.unavailableReason()).isNull();
        assertThat(outcome.tip().tipType()).isEqualTo(CoachingTipType.NON_RESPONSE);
        assertThat(outcome.tip().message()).contains("전체 학생의 40%가 질문에 응답하지 않았어요.");
        assertThat(outcome.tip().targetConcept()).isNull();
        assertThat(histories).singleElement().satisfies(history -> {
            assertThat(history.transcript().status()).isEqualTo(CoachingTranscriptStatus.SKIPPED_NOT_REQUIRED);
            assertThat(history.tip()).isEqualTo(outcome.tip());
        });
    }

    @Test
    @DisplayName("고정 문구 팁은 큐가 차 있어도 뜬다 — executor 를 거치지 않는다")
    void completesFixedTipEvenWhenQueueIsFull() {
        GenerateCoachingTipService service = service(
                rejectingExecutor(),
                command -> {
                    throw new AssertionError("전사를 부르지 않아야 한다");
                },
                request -> null,
                Duration.ofSeconds(10));

        service.start(fixedTipRequest());

        assertThat(onlyStored().tip()).isNotNull();
    }

    @Test
    @DisplayName("개념이 필요한 유형은 전사 뒤 GMS 개념으로 문구를 완성한다")
    void completesConceptTip() {
        GenerateCoachingTipService service =
                service(transcribing("제네릭 와일드카드를 설명했습니다."), concept(new TipConcept("제네릭 와일드카드", 0.9)));

        service.start(conceptTipRequest());

        CoachingOutcome outcome = onlyStored();
        assertThat(outcome.tip().tipType()).isEqualTo(CoachingTipType.CONFUSED);
        assertThat(outcome.tip().message()).contains("제네릭 와일드카드를 다른 예시로 다시 설명해 주세요.");
        assertThat(outcome.tip().targetConcept()).isEqualTo("제네릭 와일드카드");
        assertThat(captured).containsExactly(new CaptureAudioClipCommand(SESSION_ID));
        assertThat(histories).singleElement().satisfies(history -> {
            assertThat(history.transcript().status()).isEqualTo(CoachingTranscriptStatus.TRANSCRIBED);
            assertThat(history.transcript().startedAt()).isEqualTo(Instant.ofEpochMilli(1_000_000));
            assertThat(history.transcript().endedAt()).isEqualTo(Instant.ofEpochMilli(1_300_000));
            assertThat(history.topic()).isEqualTo(outcome.tip().targetConcept());
            assertThat(history.responseCounts().confused()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("수업 경과 분은 작업 실행 시각이 아니라 트리거 시각으로 잰다")
    void measuresElapsedMinutesFromTriggerTime() {
        GenerateCoachingTipService service =
                service(transcribing("제네릭 와일드카드를 설명했습니다."), concept(new TipConcept("제네릭", 0.9)));

        service.start(conceptTipRequest());

        // 01:00 시작, 01:42 트리거 → 42분. 실행 시각(01:42:01)이 아니라 트리거 시각 기준이다.
        assertThat(extracted).hasSize(1);
        assertThat(extracted.getFirst().elapsedMinutes()).isEqualTo(42);
        assertThat(extracted.getFirst().lectureTitle()).isEqualTo("자료구조와 알고리즘");
    }

    @Test
    @DisplayName("전사 텍스트가 상한을 넘으면 마지막 구간만 프롬프트에 넣는다")
    void sendsOnlyTranscriptTail() {
        String transcript = "가".repeat(3500);
        GenerateCoachingTipService service = service(transcribing(transcript), concept(new TipConcept("개념", 0.9)));

        service.start(conceptTipRequest());

        assertThat(extracted.getFirst().transcriptTail()).hasSize(3000);
    }

    @Test
    @DisplayName("큐가 차면 pending 을 남기지 않고 즉시 실패로 끝낸다")
    void endsPendingWhenQueueIsFull() {
        GenerateCoachingTipService service = service(
                rejectingExecutor(), transcribing("제네릭"), concept(new TipConcept("개념", 0.9)), Duration.ofSeconds(10));

        service.start(conceptTipRequest());

        assertThat(onlyStored().unavailableReason()).isEqualTo(CoachingTipUnavailableReason.TIP_GENERATION_FAILED);
        assertThat(captured).isEmpty();
        assertThat(extracted).isEmpty();
    }

    @Test
    @DisplayName("트리거 지연이 상한을 넘으면 전사·GMS 를 부르지 않는다 — 트리거 당시가 아닌 발화가 섞인다")
    void skipsTranscriptionWhenTriggerDelayExceedsLimit() {
        GenerateCoachingTipService service = service(
                DIRECT,
                transcribing("제네릭"),
                concept(new TipConcept("개념", 0.9)),
                // 고정 시계가 트리거 +1초라 상한을 500ms 로 두면 초과한다.
                Duration.ofMillis(500));

        service.start(conceptTipRequest());

        assertThat(onlyStored().unavailableReason()).isEqualTo(CoachingTipUnavailableReason.TIP_GENERATION_FAILED);
        assertThat(captured).isEmpty();
        assertThat(extracted).isEmpty();
    }

    @Test
    @DisplayName("전사를 건너뛴 트리거는 NO_TRANSCRIPT 다")
    void reportsNoTranscriptWhenNothingWasSaid() {
        GenerateCoachingTipService service =
                service(command -> new CaptureAudioClipResult(false, null, 30_000, null, null), request -> {
                    throw new AssertionError("전사가 없으면 GMS 를 부르지 않아야 한다");
                });

        service.start(conceptTipRequest());

        assertThat(onlyStored().unavailableReason()).isEqualTo(CoachingTipUnavailableReason.NO_TRANSCRIPT);
    }

    @Test
    @DisplayName("전사 텍스트가 비어 있으면 NO_TRANSCRIPT 다")
    void reportsNoTranscriptWhenTranscriptIsBlank() {
        GenerateCoachingTipService service = service(transcribing("   "), request -> {
            throw new AssertionError("전사가 비면 GMS 를 부르지 않아야 한다");
        });

        service.start(conceptTipRequest());

        assertThat(onlyStored().unavailableReason()).isEqualTo(CoachingTipUnavailableReason.NO_TRANSCRIPT);
    }

    @Test
    @DisplayName("전사 호출이 실패하면 TRANSCRIPTION_FAILED 다")
    void reportsTranscriptionFailed() {
        GenerateCoachingTipService service = service(
                command -> {
                    throw new AudioClipTranscriptionFailedException(new IllegalStateException("timeout"));
                },
                request -> null);

        service.start(conceptTipRequest());

        assertThat(onlyStored().unavailableReason()).isEqualTo(CoachingTipUnavailableReason.TRANSCRIPTION_FAILED);
    }

    @Test
    @DisplayName("세션 조회 실패는 전사 실패와 다른 사유다 — 같이 묶으면 GMS 가 느린 줄로 읽힌다")
    void separatesSessionLookupFailureFromTranscriptionFailure() {
        GenerateCoachingTipService service = new GenerateCoachingTipService(
                DIRECT,
                Clock.fixed(TRIGGERED_AT.plusSeconds(1), ZoneOffset.UTC),
                query -> {
                    throw new SessionNotFoundException();
                },
                command -> {
                    throw new AssertionError("세션 조회가 실패하면 전사를 부르지 않아야 한다");
                },
                request -> null,
                statePort(),
                histories::add,
                new CoachTipProperties(0.5, 100, 3000),
                new CoachPipelineProperties(2, 6, Duration.ofSeconds(10)));

        service.start(conceptTipRequest());

        assertThat(onlyStored().unavailableReason()).isEqualTo(CoachingTipUnavailableReason.TIP_GENERATION_FAILED);
    }

    @Test
    @DisplayName("개념 추출이 기술적으로 실패하면 TIP_GENERATION_FAILED 다")
    void reportsTipGenerationFailed() {
        GenerateCoachingTipService service = service(transcribing("제네릭 와일드카드"), concept(null));

        service.start(conceptTipRequest());

        assertThat(onlyStored().unavailableReason()).isEqualTo(CoachingTipUnavailableReason.TIP_GENERATION_FAILED);
    }

    @Test
    @DisplayName("근거가 없거나 신뢰도가 하한에 못 미치면 LOW_CONFIDENCE 다")
    void reportsLowConfidence() {
        GenerateCoachingTipService groundless = service(transcribing("인사만 했습니다"), concept(TipConcept.groundless()));
        groundless.start(conceptTipRequest());
        assertThat(onlyStored().unavailableReason()).isEqualTo(CoachingTipUnavailableReason.LOW_CONFIDENCE);

        stored.clear();
        GenerateCoachingTipService lowConfidence =
                service(transcribing("제네릭 와일드카드"), concept(new TipConcept("제네릭", 0.4)));
        lowConfidence.start(conceptTipRequest());
        assertThat(onlyStored().unavailableReason()).isEqualTo(CoachingTipUnavailableReason.LOW_CONFIDENCE);
    }

    @Test
    @DisplayName("결과 보관이 실패해도 예외를 밖으로 내지 않는다 — 되돌릴 경로가 없다")
    void swallowsStoreFailure() {
        GenerateCoachingTipService service = new GenerateCoachingTipService(
                DIRECT,
                Clock.fixed(TRIGGERED_AT.plusSeconds(1), ZoneOffset.UTC),
                sessionContext(),
                transcribing("제네릭 와일드카드"),
                concept(new TipConcept("제네릭", 0.9)),
                failingStatePort(),
                histories::add,
                new CoachTipProperties(0.5, 100, 3000),
                new CoachPipelineProperties(2, 6, Duration.ofSeconds(10)));

        service.start(conceptTipRequest());

        assertThat(stored).isEmpty();
        assertThat(histories).hasSize(1);
    }

    @Test
    @DisplayName("DB history failure does not block the coaching result")
    void historyFailureDoesNotBlockCoachingResult() {
        GenerateCoachingTipService service = new GenerateCoachingTipService(
                DIRECT,
                Clock.fixed(TRIGGERED_AT.plusSeconds(1), ZoneOffset.UTC),
                sessionContext(),
                transcribing("concept transcript"),
                concept(new TipConcept("concept", 0.9)),
                statePort(),
                history -> {
                    throw new IllegalStateException("mysql down");
                },
                new CoachTipProperties(0.5, 100, 3000),
                new CoachPipelineProperties(2, 6, Duration.ofSeconds(10)));

        service.start(conceptTipRequest());

        assertThat(stored).hasSize(1);
    }

    private Executor rejectingExecutor() {
        return command -> {
            throw new RejectedExecutionException("queue full");
        };
    }

    private CoachingTriggerStatePort failingStatePort() {
        return new CoachingTriggerStatePort() {
            @Override
            public boolean openTrigger(long sessionId, String triggerId, Duration cooldown) {
                return true;
            }

            @Override
            public Optional<CoachingOutcome> openOutcome(long sessionId) {
                return Optional.empty();
            }

            @Override
            public void completeOutcome(long sessionId, CoachingOutcome outcome) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public Optional<PreviousCoachingTip> previousTip(long sessionId) {
                return Optional.empty();
            }
        };
    }
}
