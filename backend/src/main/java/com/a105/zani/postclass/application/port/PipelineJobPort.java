package com.a105.zani.postclass.application.port;

import java.time.Instant;
import java.util.Optional;

import com.a105.zani.postclass.application.exception.PipelineJobUnavailableException;
import com.a105.zani.postclass.domain.model.PipelineStatus;

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

    /**
     * 세션의 작업 단계를 <b>다른 전이가 끼어들지 못하게 잡아 두고</b> 읽는다. 작업이 없으면 빈 값.
     *
     * <p>일반 조회가 아닌 이유: 단계를 옮기는 워커는 여럿이고 서로 다른 트랜잭션에서 같은 순간에 들어온다. 읽고 나서 쓰면 둘 다 같은 단계를 읽어 둘 다 옮기므로, 같은 단계가 두 번 수행되거나 한
     * 단계가 통째로 건너뛰어진다. 잠금 읽기는 뒤에 온 쪽을 앞선 전이가 커밋할 때까지 세워 두고, 그 다음 최신 커밋본을 보여 준다 — 뒤에 온 쪽이 자기 요청을 중복 보고로 알아볼 수 있는 것은 이
     * 때문이다.
     *
     * <p>{@link #updateStatus} 와 같은 트랜잭션에서 불러야 한다. 트랜잭션이 끊기면 잠금도 함께 풀려 아무것도 막지 못한다.
     *
     * @throws PipelineJobUnavailableException 작업 저장소를 읽을 수 없음
     */
    Optional<PipelineStatus> findStatusForUpdate(Long sessionId);

    /**
     * 세션의 작업 단계를 바꾼다.
     *
     * <p>조건 없이 덮어쓰므로, 갈 수 있는 단계인지는 {@link #findStatusForUpdate} 로 잠근 뒤 호출자가 판단한다.
     *
     * @throws PipelineJobUnavailableException 작업 저장소에 쓸 수 없음
     */
    void updateStatus(Long sessionId, PipelineStatus status, Instant changedAt);
}
