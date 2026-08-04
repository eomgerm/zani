package com.a105.zani.postclass.application.transcribesession;

/**
 * 세션의 전사를 끝까지 수행한다(S15P11A105-247).
 *
 * <p>실행권은 이미 선점된 상태로 들어온다({@code TryStartTranscriptionUseCase}). 이 호출은 그 뒤의 실제 작업이다.
 *
 * <pre>
 * 트랙 목록 조회 → 마이크만 고르기
 *   트랙마다 (직렬): 분할 → 체크포인트 등록 → 청크 전사(병렬) → 임시파일 정리
 * 조립·저장 → ANALYZING 전이
 * </pre>
 *
 * <p><b>트랜잭션이 이 호출을 감싸지 않는다.</b> FFmpeg 과 GMS 를 기다리는 동안 행 잠금을 붙잡으면 SLA 경보와 다른 단계의 전이가 잠금 대기로 실패한다. 안에서 짧은 트랜잭션을 여러 번
 * 쓴다.
 *
 * <p><b>실패를 던지지 않는다.</b> 파이프라인 실패 기록까지가 이 호출의 책임이다 — 호출자(스케줄러)는 디스패치만 하고 결과를 기다리지 않으므로, 예외를 올려도 받을 사람이 없다.
 *
 * <p><b>실패는 실행 한 번에 한 번만 보고한다.</b> 청크 열 개가 모두 429 를 받아도 {@code pipeline_jobs} 의 시도 횟수는 1 만 오른다. 청크마다 보고하면 한 번 실행했는데 단계의
 * 5회 예산이 여러 번 차감돼, 실제로는 두세 번밖에 시도하지 않은 세션이 상한에 걸린다. 청크별 실패는 체크포인트 행에 각각 남는다.
 */
public interface TranscribeSessionUseCase {

    /** 실패해도 예외를 올리지 않는다. 실패는 {@code pipeline_jobs} 에 기록된다. */
    void transcribe(Long sessionId);
}
