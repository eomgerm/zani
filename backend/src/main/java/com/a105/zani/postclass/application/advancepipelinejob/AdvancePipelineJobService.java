package com.a105.zani.postclass.application.advancepipelinejob;

import java.time.Clock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.postclass.application.exception.IllegalPipelineTransitionException;
import com.a105.zani.postclass.application.exception.PipelineJobNotFoundException;
import com.a105.zani.postclass.application.port.PipelineJobPort;
import com.a105.zani.postclass.domain.model.PipelineStateMachine;
import com.a105.zani.postclass.domain.model.PipelineStatus;

/**
 * 사후 처리 작업을 다음 단계로 옮긴다(FRD §17.1 처리 순서).
 *
 * <p>같은 단계를 두 번 보고하는 것은 오류가 아니라 정상이다 — 워커가 재시작하거나 스케줄이 겹치면 이미 끝낸 단계를 다시 보고한다. 그때마다 실패로 돌려주면 멀쩡히 진행 중인 수업의 결과가 끊긴다. 그래서
 * 이 서비스는 요청한 단계에 이미 있으면 아무것도 바꾸지 않고 성공으로 돌려주고({@code advancedNow=false}), 단계를 실제로 옮긴 호출자에게만 {@code true} 를 준다.
 *
 * <p>읽고 나서 쓰는 대신 잠금 읽기로 시작하는 이유는 {@link PipelineJobPort#findStatusForUpdate} 에 적어 두었다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdvancePipelineJobService implements AdvancePipelineJobUseCase {

    private final PipelineJobPort pipelineJobPort;
    private final Clock clock;

    @Override
    @Transactional
    public AdvancePipelineJobResult advance(AdvancePipelineJobCommand command) {
        PipelineStatus current =
                pipelineJobPort.findStatusForUpdate(command.sessionId()).orElseThrow(PipelineJobNotFoundException::new);

        if (current == command.targetStatus()) {
            log.debug("사후 처리 작업이 이미 요청한 단계입니다. sessionId={}, status={}", command.sessionId(), current);
            return new AdvancePipelineJobResult(current, false);
        }
        if (!PipelineStateMachine.canAdvance(current, command.targetStatus())) {
            throw new IllegalPipelineTransitionException();
        }

        pipelineJobPort.updateStatus(command.sessionId(), command.targetStatus(), clock.instant());
        return new AdvancePipelineJobResult(command.targetStatus(), true);
    }
}
