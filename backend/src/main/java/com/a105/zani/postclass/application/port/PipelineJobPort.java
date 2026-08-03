package com.a105.zani.postclass.application.port;

import java.time.Instant;

import com.a105.zani.postclass.application.exception.PipelineJobUnavailableException;

/** 사후 처리 작업 큐. 강사 메모가 확정되면 전사·AI 분석이 시작되도록 작업 하나를 남긴다(FRD §16 NOTE-004). */
public interface PipelineJobPort {

    /**
     * 세션의 사후 처리 작업을 대기 상태로 등록한다.
     *
     * <p>세션당 하나만 존재하므로 이미 있으면 아무것도 하지 않고 {@code false} 를 돌려준다 — 중복 확정이나 재시도로 같은 세션의 작업이 두 번 만들어지지 않는다. 이 호출은 확정과 같은
     * 트랜잭션에서 일어나야 한다. 확정만 커밋되고 작업이 남지 않으면 분석이 영원히 시작되지 않는다.
     *
     * @return 이번 호출로 작업이 만들어졌으면 {@code true}
     * @throws PipelineJobUnavailableException 작업 저장소에 쓸 수 없음
     */
    boolean enqueue(Long sessionId, Instant queuedAt);
}
