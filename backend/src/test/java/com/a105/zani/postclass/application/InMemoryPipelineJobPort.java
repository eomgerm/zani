package com.a105.zani.postclass.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.a105.zani.postclass.application.exception.PipelineJobUnavailableException;
import com.a105.zani.postclass.application.port.PipelineJobPort;
import com.a105.zani.postclass.application.port.PipelineJobState;
import com.a105.zani.postclass.domain.model.PipelineStateMachine;
import com.a105.zani.postclass.domain.model.PipelineStatus;

/** 사후 처리 작업 큐의 in-memory 대역. 세션당 하나만 받는 실제 계약(session_id UNIQUE)을 그대로 지킨다. */
public class InMemoryPipelineJobPort implements PipelineJobPort {

    /** 등록이 실제로 일어난 순서. 세션당 한 번만 늘어난다. */
    public final List<Long> enqueuedSessionIds = new ArrayList<>();

    private final Map<Long, Row> rows = new HashMap<>();

    /** 다음 등록이 저장소 장애로 실패한다. */
    public boolean failEnqueue;

    /** 실제 테이블의 한 행. 재시도 상태까지 그대로 들고 있어야 단계별 예산 초기화를 검증할 수 있다. */
    private static final class Row {
        private PipelineStatus status;
        private int attemptCount;
        private final Instant queuedAt;
        private Instant nextAttemptAt;
        private String lastError;
        private Instant changedAt;

        private Row(PipelineStatus status, Instant queuedAt) {
            this.status = status;
            this.queuedAt = queuedAt;
            this.changedAt = queuedAt;
        }
    }

    @Override
    public boolean enqueue(Long sessionId, Instant queuedAt) {
        if (failEnqueue) {
            throw new PipelineJobUnavailableException(new IllegalStateException("db down"));
        }
        if (rows.containsKey(sessionId)) {
            return false;
        }
        enqueuedSessionIds.add(sessionId);
        rows.put(sessionId, new Row(PipelineStatus.QUEUED, queuedAt));
        return true;
    }

    @Override
    public Optional<PipelineJobState> findForUpdate(Long sessionId) {
        return Optional.ofNullable(rows.get(sessionId))
                .map(row -> new PipelineJobState(row.status, row.attemptCount, row.queuedAt, row.nextAttemptAt));
    }

    @Override
    public void updateStatus(Long sessionId, PipelineStatus status, Instant changedAt) {
        Row row = rows.get(sessionId);
        row.status = status;
        row.changedAt = changedAt;
        // 재시도 예산은 단계마다 새로 준다. 실패 사유는 이력이라 지우지 않는다.
        row.attemptCount = 0;
        row.nextAttemptAt = null;
    }

    @Override
    public void markRetry(Long sessionId, String error, Instant nextAttemptAt, Instant changedAt) {
        Row row = rows.get(sessionId);
        row.attemptCount++;
        row.nextAttemptAt = nextAttemptAt;
        row.lastError = error;
        row.changedAt = changedAt;
    }

    @Override
    public void markFailed(Long sessionId, String error, Instant changedAt) {
        Row row = rows.get(sessionId);
        row.lastError = error;
        row.nextAttemptAt = null;
        row.changedAt = changedAt;
    }

    @Override
    public List<Long> findDueTranscriptionSessionIds(Instant now, int limit) {
        // 실제 쿼리와 같은 조건: QUEUED 전체 + 재시도 기한이 지난 TRANSCRIBING. 실행 중(대기 시각 없음)은 빼야 한다.
        return rows.entrySet().stream()
                .filter(entry -> entry.getValue().status == PipelineStatus.QUEUED
                        || (entry.getValue().status == PipelineStatus.TRANSCRIBING
                                && entry.getValue().nextAttemptAt != null
                                && !entry.getValue().nextAttemptAt.isAfter(now)))
                .sorted(java.util.Comparator.comparing(entry -> entry.getValue().queuedAt))
                .map(java.util.Map.Entry::getKey)
                .limit(limit)
                .toList();
    }

    @Override
    public void clearRetryWait(Long sessionId, Instant changedAt) {
        Row row = rows.get(sessionId);
        // 단계와 시도 횟수는 그대로 둔다. 대기만 푸는 것이 이 계약의 전부다.
        row.nextAttemptAt = null;
        row.changedAt = changedAt;
    }

    @Override
    public List<Long> findOverdueSessionIds(Instant queuedBefore, int limit) {
        return rows.entrySet().stream()
                .filter(entry -> PipelineStateMachine.unfinishedStages().contains(entry.getValue().status))
                .filter(entry -> !entry.getValue().queuedAt.isAfter(queuedBefore))
                .sorted(Comparator.comparing(entry -> entry.getValue().queuedAt))
                .map(Map.Entry::getKey)
                .limit(limit)
                .toList();
    }

    /** 저장된 단계. 등록되지 않은 세션이면 빈 값. */
    public Optional<PipelineStatus> statusOf(Long sessionId) {
        return Optional.ofNullable(rows.get(sessionId)).map(row -> row.status);
    }

    /** 단계가 마지막으로 바뀐 시각. 8시간 SLA 를 재는 쪽이 보는 값이라 등록 시각에 머물지 않는지 확인한다. */
    public Optional<Instant> changedAtOf(Long sessionId) {
        return Optional.ofNullable(rows.get(sessionId)).map(row -> row.changedAt);
    }

    public Optional<Integer> attemptCountOf(Long sessionId) {
        return Optional.ofNullable(rows.get(sessionId)).map(row -> row.attemptCount);
    }

    public Optional<Instant> nextAttemptAtOf(Long sessionId) {
        return Optional.ofNullable(rows.get(sessionId)).map(row -> row.nextAttemptAt);
    }

    public Optional<String> lastErrorOf(Long sessionId) {
        return Optional.ofNullable(rows.get(sessionId)).map(row -> row.lastError);
    }
}
