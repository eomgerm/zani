package com.a105.zani.postclass.application.claimanalysis;

import java.time.Duration;

/**
 * 세션의 분석 실행권을 임대로 선점한다(S15P11A105-304).
 *
 * <p>{@code findDueAnalysisSessionIds} 는 후보만 준다. 실제 시작 여부는 여기서 행을 잠그고 다시 확인해 정한다 — 조회와 시작 사이에 다른 실행이 끼어들 수 있다.
 *
 * <p><b>전사와 달리 단계를 옮기지 않는다.</b> 전사는 {@code QUEUED → TRANSCRIBING} 전이가 곧 선점이었지만, 분석은 {@code ANALYZING} 하나로 "할 일이 남았다" 와
 * "지금 하는 중이다" 를 모두 나타내야 한다. 다음 단계인 {@code VALIDATING} 은 세 분석이 모두 끝나야 가는 자리라 미리 옮길 수 없다. 그래서 다음 시도 시각을 임대 만료로 밀어 표시한다.
 *
 * <p><b>이 호출은 짧아야 한다.</b> 안에서 잠금 읽기를 하므로 GMS 호출을 이 트랜잭션에 담으면 {@code pipeline_jobs} 행 잠금을 수십 분 붙잡는다. 그러면 SLA 경보와 다른 단계의
 * 전이가 {@code innodb_lock_wait_timeout} 에 걸려 실패한다. 호출자는 {@code true} 를 받은 뒤 트랜잭션 밖에서 실제 분석을 시작한다.
 */
public interface TryClaimAnalysisUseCase {

    /**
     * @param lease 이 시간만큼 실행권을 잡는다. 만료되면 다른 실행이 이어받으므로, 한 세션의 세 분석이 끝나기에 넉넉해야 한다
     * @return 이번 호출이 실행권을 얻었으면 {@code true}. 이미 누가 쥐고 있거나 대상이 아니면 {@code false}
     */
    boolean tryClaim(Long sessionId, Duration lease);
}
