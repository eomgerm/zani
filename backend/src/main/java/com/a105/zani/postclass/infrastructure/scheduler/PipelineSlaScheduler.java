package com.a105.zani.postclass.infrastructure.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.a105.zani.postclass.application.alertoverduejobs.AlertOverduePipelineJobsUseCase;

/**
 * 8시간 마감을 넘긴 사후 처리 작업을 주기적으로 확인한다. 판정·경보는 유스케이스가 갖고, 이 어댑터는 주기 실행만 담당한다.
 *
 * <p>주기가 분 단위인 이유: 마감은 8시간 단위라 초 단위로 볼 이유가 없고, 스케줄러가 멈춘 동안 밀린 것도 시각 기준 판정이라 다음 실행에서 그대로 잡힌다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineSlaScheduler {

    private final AlertOverduePipelineJobsUseCase alertOverduePipelineJobsUseCase;

    @Scheduled(fixedDelayString = "${postclass.pipeline-sla-check-delay:PT5M}")
    public void checkSla() {
        try {
            alertOverduePipelineJobsUseCase.alertOverdueJobs();
        } catch (RuntimeException exception) {
            // 스케줄러는 예외를 삼켜 다음 주기를 살리므로, 여기서 스택트레이스를 남기지 않으면 원인이 사라진다.
            log.warn("Post-class pipeline SLA check failed", exception);
        }
    }
}
