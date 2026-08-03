package com.a105.zani.postclass.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.a105.zani.postclass.application.exception.PipelineJobUnavailableException;
import com.a105.zani.postclass.application.port.PipelineJobPort;
import com.a105.zani.postclass.domain.model.PipelineStatus;

/** 사후 처리 작업 큐의 in-memory 대역. 세션당 하나만 받는 실제 계약(session_id UNIQUE)을 그대로 지킨다. */
public class InMemoryPipelineJobPort implements PipelineJobPort {

    /** 등록이 실제로 일어난 순서. 세션당 한 번만 늘어난다. */
    public final List<Long> enqueuedSessionIds = new ArrayList<>();

    private final Map<Long, PipelineStatus> statusBySessionId = new HashMap<>();
    private final Map<Long, Instant> changedAtBySessionId = new HashMap<>();

    /** 다음 등록이 저장소 장애로 실패한다. */
    public boolean failEnqueue;

    @Override
    public boolean enqueue(Long sessionId, Instant queuedAt) {
        if (failEnqueue) {
            throw new PipelineJobUnavailableException(new IllegalStateException("db down"));
        }
        if (statusBySessionId.containsKey(sessionId)) {
            return false;
        }
        enqueuedSessionIds.add(sessionId);
        statusBySessionId.put(sessionId, PipelineStatus.QUEUED);
        changedAtBySessionId.put(sessionId, queuedAt);
        return true;
    }

    @Override
    public Optional<PipelineStatus> findStatusForUpdate(Long sessionId) {
        return Optional.ofNullable(statusBySessionId.get(sessionId));
    }

    @Override
    public void updateStatus(Long sessionId, PipelineStatus status, Instant changedAt) {
        statusBySessionId.put(sessionId, status);
        changedAtBySessionId.put(sessionId, changedAt);
    }

    /** 저장된 단계. 등록되지 않은 세션이면 빈 값. */
    public Optional<PipelineStatus> statusOf(Long sessionId) {
        return Optional.ofNullable(statusBySessionId.get(sessionId));
    }

    /** 단계가 마지막으로 바뀐 시각. 8시간 SLA 를 재는 쪽이 보는 값이라 등록 시각에 머물지 않는지 확인한다. */
    public Optional<Instant> changedAtOf(Long sessionId) {
        return Optional.ofNullable(changedAtBySessionId.get(sessionId));
    }
}
