package com.a105.zani.postclass.application.alertoverduejobs;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.postclass.application.port.PipelineJobPort;
import com.a105.zani.postclass.domain.model.PostClassRetryPolicy;

/**
 * 메모 확정 후 8시간이 지나도 끝나지 않은 작업을 경보한다(FRD AI-006, NFR-PERF-004).
 *
 * <p>작업을 건드리지 않고 알리기만 한다. 마감을 넘겼다고 자동으로 접으면, 늦더라도 곧 끝날 작업까지 결과 없이 버려진다 — 무엇을 할지는 사람이 정한다.
 *
 * <p>경보 수단은 로그다. 게이지도 함께 올리지만 지금은 actuator 가 health 만 노출하고 수집 registry 도 없어 밖에서 읽을 수 없다 — 노출 설정과 수집 인프라가 갖춰지면 코드 변경 없이
 * 그대로 드러난다.
 */
@Slf4j
@Service
public class AlertOverduePipelineJobsService implements AlertOverduePipelineJobsUseCase {

    private static final int BATCH_SIZE = 50;

    private final PipelineJobPort pipelineJobPort;
    private final Clock clock;
    private final AtomicInteger overdueCount = new AtomicInteger();

    public AlertOverduePipelineJobsService(PipelineJobPort pipelineJobPort, Clock clock, MeterRegistry meterRegistry) {
        this.pipelineJobPort = pipelineJobPort;
        this.clock = clock;
        meterRegistry.gauge("postclass.pipeline.overdue.jobs", overdueCount);
    }

    @Override
    public int alertOverdueJobs() {
        Instant queuedBefore = clock.instant().minus(PostClassRetryPolicy.SLA);
        List<Long> overdue = pipelineJobPort.findOverdueSessionIds(queuedBefore, BATCH_SIZE);
        overdueCount.set(overdue.size());

        if (!overdue.isEmpty()) {
            // 세션 ID 를 함께 남긴다. 건수만으로는 어느 수업이 밀렸는지 찾을 수 없다.
            log.error(
                    "사후 처리가 8시간을 넘겼습니다. count={}, sessionIds={}{}",
                    overdue.size(),
                    overdue,
                    overdue.size() == BATCH_SIZE ? " (표시 상한, 더 있을 수 있음)" : "");
        }
        return overdue.size();
    }
}
