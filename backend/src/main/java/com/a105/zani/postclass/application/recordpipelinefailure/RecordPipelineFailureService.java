package com.a105.zani.postclass.application.recordpipelinefailure;

import java.time.Clock;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.postclass.application.advancepipelinejob.AdvancePipelineJobCommand;
import com.a105.zani.postclass.application.advancepipelinejob.AdvancePipelineJobUseCase;
import com.a105.zani.postclass.application.exception.PipelineJobNotFoundException;
import com.a105.zani.postclass.application.port.PipelineJobPort;
import com.a105.zani.postclass.application.port.PipelineJobState;
import com.a105.zani.postclass.domain.model.PipelineStatus;
import com.a105.zani.postclass.domain.model.PostClassRetryPolicy;
import com.a105.zani.postclass.domain.model.RetryDecision;

/**
 * 사후 처리 단계의 실패를 기록하고 다시 시도할지 정한다(FRD AI-006).
 *
 * <p>재시도는 단계를 되돌리지 않는다. 실패한 단계를 그대로 두고 다음 시도 시각만 미뤄, 그 시각 이후에 같은 단계를 다시 시도하게 한다. 접기로 하면 상태 머신을 거쳐 FAILED 로 옮기므로, 이미 공개된
 * 작업을 실패로 되돌리는 일은 일어나지 않는다.
 *
 * <p><b>호출자 트랜잭션의 영속성 컨텍스트가 비워진다</b> — 이유는 {@link AdvancePipelineJobUseCase} 구현의 설명과 같다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordPipelineFailureService implements RecordPipelineFailureUseCase {

    private final PipelineJobPort pipelineJobPort;
    private final AdvancePipelineJobUseCase advancePipelineJobUseCase;
    private final Clock clock;

    @Override
    @Transactional
    public RecordPipelineFailureResult record(RecordPipelineFailureCommand command) {
        PipelineJobState job =
                pipelineJobPort.findForUpdate(command.sessionId()).orElseThrow(PipelineJobNotFoundException::new);

        Instant now = clock.instant();
        // 이번 실패까지 포함한 시도 횟수로 판단한다. 저장된 값은 아직 이번 실패를 세지 않았다.
        int attemptCount = job.attemptCount() + 1;
        RetryDecision decision = PostClassRetryPolicy.decide(attemptCount, job.queuedAt(), now, command.retryable());

        if (decision.shouldRetry()) {
            pipelineJobPort.markRetry(command.sessionId(), command.reason(), decision.nextAttemptAt(), now);
            log.warn(
                    "사후 처리 {} 단계가 실패해 재시도합니다. sessionId={}, attempt={}, nextAttemptAt={}, reason={}",
                    job.status(),
                    command.sessionId(),
                    attemptCount,
                    decision.nextAttemptAt(),
                    command.reason());
            return new RecordPipelineFailureResult(job.status(), decision.nextAttemptAt(), null);
        }

        // 사유 기록과 전이는 한 트랜잭션이다. 상태 머신이 전이를 거절하면(이미 공개된 작업에 늦은 실패 보고가 온 경우)
        // 이 기록도 함께 롤백되는데, 그것이 맞다 — 공개된 결과에 실패 사유만 붙어 남으면 그쪽이 더 헷갈린다.
        pipelineJobPort.markFailed(command.sessionId(), command.reason(), now);
        advancePipelineJobUseCase.advance(new AdvancePipelineJobCommand(command.sessionId(), PipelineStatus.FAILED));
        log.error(
                "사후 처리 {} 단계를 접습니다. sessionId={}, attempt={}, giveUpReason={}, reason={}",
                job.status(),
                command.sessionId(),
                attemptCount,
                decision.giveUpReason(),
                command.reason());
        return new RecordPipelineFailureResult(PipelineStatus.FAILED, null, decision.giveUpReason());
    }
}
