package com.a105.zani.postclass.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.a105.zani.postclass.application.exception.PipelineJobUnavailableException;
import com.a105.zani.postclass.application.port.PipelineJobPort;

/** 사후 처리 작업 큐의 in-memory 대역. 세션당 하나만 받는 실제 계약(session_id UNIQUE)을 그대로 지킨다. */
public class InMemoryPipelineJobPort implements PipelineJobPort {

    public final List<Long> enqueuedSessionIds = new ArrayList<>();

    /** 다음 등록이 저장소 장애로 실패한다. */
    public boolean failEnqueue;

    @Override
    public boolean enqueue(Long sessionId, Instant queuedAt) {
        if (failEnqueue) {
            throw new PipelineJobUnavailableException(new IllegalStateException("db down"));
        }
        if (enqueuedSessionIds.contains(sessionId)) {
            return false;
        }
        enqueuedSessionIds.add(sessionId);
        return true;
    }
}
