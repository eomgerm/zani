package com.a105.zani.postclass.application.starttranscription;

/**
 * 세션의 전사 실행권을 선점한다(S15P11A105-247).
 *
 * <p>{@code findDueTranscriptionSessionIds} 는 후보만 준다. 실제 시작 여부는 여기서 행을 잠그고 다시 확인해 정한다 — 조회와 시작 사이에 다른 실행이 끼어들 수 있다.
 *
 * <p><b>이 호출은 짧아야 한다.</b> 안에서 잠금 읽기를 하므로 FFmpeg·GMS 를 이 트랜잭션에 담으면 {@code pipeline_jobs} 행 잠금을 수십 분 붙잡는다. 그러면 SLA 경보와 다른
 * 단계의 전이가 {@code innodb_lock_wait_timeout} 에 걸려 실패한다. 호출자는 {@code true} 를 받은 뒤 트랜잭션 밖에서 실제 작업을 시작한다.
 */
public interface TryStartTranscriptionUseCase {

    /** @return 이번 호출이 실행권을 얻었으면 {@code true}. 이미 실행 중이거나 대상이 아니면 {@code false} */
    boolean tryStart(Long sessionId);
}
