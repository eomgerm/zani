package com.a105.zani.postclass.infrastructure.scheduler;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.a105.zani.postclass.application.port.PipelineJobPort;
import com.a105.zani.postclass.application.starttranscription.TryStartTranscriptionUseCase;
import com.a105.zani.postclass.application.transcribesession.TranscribeSessionUseCase;
import com.a105.zani.postclass.infrastructure.config.PostClassTranscriptionExecutorConfig;
import com.a105.zani.postclass.infrastructure.config.PostClassTranscriptionProperties;

/**
 * 전사를 기다리는 작업을 찾아 전용 실행기로 넘긴다(S15P11A105-247).
 *
 * <p><b>이 메서드는 즉시 반환한다.</b> 공유 {@code TaskScheduler} 는 풀 크기가 7 이고 이미 6개 작업이 나눠 쓴다. 전사는 한 세션이 수십 분을 점유할 수 있으므로 여기서 기다리면
 * 100ms 주기인 강사 오디오 무음 패딩이 굶는다 — 라이브 수업이 사후 처리 때문에 흔들린다.
 *
 * <p><b>선점을 worker 안에서 한다.</b> 순서를 뒤집으면 작업이 영구 정지한다.
 *
 * <pre>
 * 잘못된 순서: tryStart(QUEUED → TRANSCRIBING, next_attempt_at 제거) → 제출 → 거부
 *   → DB 는 "실행 중" 이고 재시도 대기도 없다
 *   → findDueTranscriptionSessionIds 가 다시 담지 않는다(실행 중인 TRANSCRIBING 은 제외)
 *   → 아무도 그 세션을 다시 보지 않는다
 * </pre>
 *
 * <p>실행기가 받은 <b>뒤에</b> 선점하면 거부 시점에 DB 가 그대로다. 그 세션은 다음 주기에 같은 조건으로 다시 발견된다. 거부되면 선점을 되돌리는 별도 전이가 필요해지는데, 그 전이는 "되돌리는 중에
 * 죽으면" 같은 새 경로를 만든다 — 애초에 바꾸지 않는 편이 간단하다.
 *
 * <p><b>제출 거부는 파이프라인 실패가 아니다.</b> 포화는 정상 동작이다(실행기가 크기 1·큐 0 인 것이 의도다). 실패로 기록하면 한 세션이 도는 동안 다른 세션들의 5회 예산이 주기마다 깎여,
 * 실제로는 한 번도 시도하지 않은 세션이 상한에 걸린다.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "postclass.transcription",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PostClassTranscriptionScheduler {

    private final Executor orchestrationExecutor;
    private final PipelineJobPort pipelineJobPort;
    private final TryStartTranscriptionUseCase tryStartTranscriptionUseCase;
    private final TranscribeSessionUseCase transcribeSessionUseCase;
    private final PostClassTranscriptionProperties properties;
    private final Clock clock;

    public PostClassTranscriptionScheduler(
            @Qualifier(PostClassTranscriptionExecutorConfig.ORCHESTRATION_EXECUTOR) Executor orchestrationExecutor,
            PipelineJobPort pipelineJobPort,
            TryStartTranscriptionUseCase tryStartTranscriptionUseCase,
            TranscribeSessionUseCase transcribeSessionUseCase,
            PostClassTranscriptionProperties properties,
            Clock clock) {
        this.orchestrationExecutor = orchestrationExecutor;
        this.pipelineJobPort = pipelineJobPort;
        this.tryStartTranscriptionUseCase = tryStartTranscriptionUseCase;
        this.transcribeSessionUseCase = transcribeSessionUseCase;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${postclass.transcription.poll-delay:PT10S}")
    public void dispatchDueTranscriptions() {
        List<Long> dueSessionIds;
        try {
            dueSessionIds =
                    pipelineJobPort.findDueTranscriptionSessionIds(clock.instant(), properties.dispatchBatchSize());
        } catch (RuntimeException exception) {
            // 스케줄러는 예외를 삼켜 다음 주기를 살리므로, 여기서 스택트레이스를 남기지 않으면 원인이 사라진다.
            log.warn("Could not read transcription candidates", exception);
            return;
        }
        for (Long sessionId : dueSessionIds) {
            if (!submit(sessionId)) {
                // 실행기가 포화면 뒤 후보도 같은 결과다. 로그만 반복해서 늘릴 이유가 없다.
                log.debug(
                        "Transcription executor is saturated, deferring {} candidates to the next tick",
                        dueSessionIds.size() - dueSessionIds.indexOf(sessionId));
                return;
            }
        }
    }

    /** @return 실행기가 받았으면 {@code true}. 포화로 거부됐으면 {@code false} 이며 DB 는 그대로다 */
    private boolean submit(Long sessionId) {
        try {
            orchestrationExecutor.execute(() -> run(sessionId));
            return true;
        } catch (RejectedExecutionException saturated) {
            return false;
        }
    }

    /**
     * 실행기 스레드에서 도는 본체.
     *
     * <p>선점이 여기 있다. 실패 기록은 {@code transcribe} 안에서 하므로 이 메서드는 예외를 올리지 않는다 — 올려도 받을 사람이 없고, 실행기의 기본 핸들러가 스레드를 죽일 수 있다.
     */
    private void run(Long sessionId) {
        try {
            if (!tryStartTranscriptionUseCase.tryStart(sessionId)) {
                // 조회와 이 시점 사이에 다른 실행이 가져갔거나 작업이 다음 단계로 넘어갔다.
                return;
            }
            transcribeSessionUseCase.transcribe(sessionId);
        } catch (RuntimeException exception) {
            log.error("Transcription worker failed outside the recorded paths: sessionId={}", sessionId, exception);
        }
    }
}
