package com.a105.zani.postclass.application.port;

import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

import com.a105.zani.postclass.application.exception.TranscriptionChunkBoundaryMismatchException;
import com.a105.zani.postclass.application.exception.TranscriptionChunkStoreUnavailableException;

/**
 * 전사 청크 체크포인트(S15P11A105-247).
 *
 * <p>이 저장소가 있는 이유는 최종 결과가 들어가는 {@code transcripts} 가 {@code UK_TRANSCRIPTS_SESSION} 때문에 세션당 한 행이라, "3번 청크 성공, 5번 실패" 같은
 * 부분 진행을 담을 자리가 없기 때문이다. 티켓의 완료 조건이 "청크 1개 실패 시 성공 청크 재호출 없이 완료" 이므로 진행 상태를 둘 곳이 필요하다.
 *
 * <p><b>트랜잭션 경계.</b> 선점({@link #tryClaim})만 짧은 트랜잭션이고, GMS 호출은 그 밖에서 한다. 호출을 트랜잭션에 담으면 청크마다 최대 180초씩 행 잠금을 붙잡아 같은 세션의
 * 다른 갱신과 SLA 경보가 잠금 대기로 실패한다.
 *
 * <pre>
 * tryClaim (짧은 tx)  →  GMS 호출 (tx 밖)  →  mark* (짧은 tx)
 * </pre>
 *
 * <p><b>lease 회수가 있으면 완료 쓰기에도 fencing 이 필요하다.</b> 회수만 두고 쓰기를 막지 않으면 이런 순서가 가능하다.
 *
 * <pre>
 * A 선점 → A 의 lease 만료 → B 가 회수 → 늦게 끝난 A 가 결과 기록 → B 도 결과 기록
 * </pre>
 *
 * <p>그래서 {@link #tryClaim} 이 실행권 토큰({@code attemptCount})을 주고, 모든 기록 호출이 그것을 조건에 넣는다. 단조 증가하는 값이라 회수될 때마다 달라지고, 늦은 쓰기는
 * 0 행을 갱신해 {@code false} 로 돌아온다. {@code leaseUntil} 대신 {@code attemptCount} 를 쓰는 이유가 이 단조성이다.
 *
 * <p><b>멱등의 근거는 {@code (recordingFileId, chunkIndex)} UNIQUE 다.</b> 재분할해도 행이 늘지 않고, 이미 {@code SUCCEEDED} 인 청크는
 * {@link #findClaimable} 이 애초에 돌려주지 않으므로 재기동 후 다시 호출되지 않는다.
 */
public interface TranscriptionChunkPort {

    /**
     * 분할 결과를 등록하고 <b>기록된 체크포인트와 대조한 뒤</b> DB 상태를 돌려준다.
     *
     * <p>이미 있는 {@code (recordingFileId, chunkIndex)} 는 건너뛴다 — 재시도로 같은 원본을 다시 잘랐을 때 행이 늘지 않아야 하고, 이미 성공한 청크의 결과를 덮어써서도 안
     * 된다.
     *
     * <p><b>그런데 건너뛰기만 하면 위험하다.</b> 재시도할 때는 임시 파일이 없어 원본을 다시 자르는데, 그 사이 청크 길이 설정이 바뀌었거나 ffmpeg 동작이 달라지면 새 청크의 구간이 저장된 값과
     * 어긋난다. 삽입이 조용히 무시되면 "DB 에 있는 옛 오프셋 + 새 청크의 상대 시각" 이라는 잘못된 조합으로 절대 시각이 만들어진다. 그래서 <b>삽입하기 전에</b> 기록된 체크포인트를 읽어
     * 개수·{@code chunkIndex}·{@code sourceStartMs}·{@code sourceEndMs} 를 모두 대조한다. 삽입 후에 대조하면 새로 늘어난 청크가 이미 들어가 있어 개수가
     * 저절로 맞는다.
     *
     * <p>반환값이 DB 상태인 것도 같은 이유다. 호출자가 넘긴 목록이 아니라 <b>저장된 값</b>을 시간축의 정본으로 쓰게 만든다.
     *
     * @return 이 원본 파일의 체크포인트 전체. 순번 오름차순
     * @throws TranscriptionChunkBoundaryMismatchException 새 분할이 기록된 경계와 다름(재시도 불가)
     * @throws TranscriptionChunkStoreUnavailableException 저장소에 쓸 수 없음
     */
    List<TranscriptionChunk> registerAll(Long sessionId, Long recordingFileId, List<AudioChunk> chunks, Instant now);

    /**
     * 지금 처리할 수 있는 청크를 순번대로 최대 limit 건.
     *
     * <p>대상은 셋이다.
     *
     * <ul>
     *   <li>{@code PENDING} 이고 재시도 대기가 없거나 기한이 지난 것
     *   <li>{@code PROCESSING} 인데 {@code lease_until} 이 지난 것 — 선점한 실행이 죽은 경우다
     * </ul>
     *
     * <p>{@code SUCCEEDED}·{@code SKIPPED_SILENT}·{@code FAILED} 는 담지 않는다. 성공 청크를 다시 호출하지 않는 근거가 여기다.
     *
     * @throws TranscriptionChunkStoreUnavailableException 저장소를 읽을 수 없음
     */
    List<TranscriptionChunk> findClaimable(Long recordingFileId, Instant now, int limit);

    /**
     * 청크를 선점하고 시도 횟수를 올린다. <b>선점과 증가는 한 트랜잭션이어야 한다.</b>
     *
     * <p>조건부 UPDATE 로 구현한다 — 조회 후 갱신으로 나누면 두 실행이 같은 청크를 함께 선점할 수 있다. 0 행이 갱신되면 다른 쪽이 먼저 가져간 것이므로 {@code false} 다.
     *
     * <p><b>반환값은 실행권 토큰이다.</b> 선점 후의 {@code attemptCount} 이고, 뒤따르는 모든 기록 호출에 그대로 넘겨야 한다. lease 가 만료돼 다른 실행이 이 청크를 회수하면
     * {@code attemptCount} 가 또 올라가므로, 늦게 끝난 이전 실행의 토큰은 더 이상 맞지 않는다 — 그것이 늦은 쓰기를 막는 방법이다.
     *
     * @param leaseUntil 이 시각까지 이 실행이 청크를 쥔다. 넘기면 회수 대상이 된다
     * @return 선점했으면 실행권 토큰, 다른 쪽이 먼저 가져갔으면 빈 값
     * @throws TranscriptionChunkStoreUnavailableException 저장소에 쓸 수 없음
     */
    OptionalInt tryClaim(Long chunkId, Instant leaseUntil, Instant now);

    /**
     * 성공을 기록한다. 세그먼트 시각은 <b>청크 기준 상대값</b>으로 저장한다.
     *
     * <p>절대 시각으로 바꿔 저장하지 않는 이유: 오프셋 계산이 잘못됐음을 나중에 알게 되면 원본 없이는 되돌릴 수 없다. 상대값을 남겨 두면 조립만 다시 하면 된다.
     *
     * @param fencingToken {@link #tryClaim} 이 준 실행권 토큰
     * @return 기록됐으면 {@code true}. 실행권을 잃었으면 {@code false} 이고 결과를 버려야 한다
     * @throws TranscriptionChunkStoreUnavailableException 저장소에 쓸 수 없음
     */
    boolean markSucceeded(Long chunkId, int fencingToken, List<TranscriptSegment> segments, Instant now);

    /**
     * 사전 무음 판별로 GMS 를 호출하지 않고 건너뛴 것을 기록한다.
     *
     * <p>{@link #markSucceeded} 에 빈 세그먼트를 넣는 것과 구분한다. 호출하지 않은 것과 호출했더니 발화가 없던 것은 다른 사실이고, 전자는 판별 임계값 문제일 수 있다.
     *
     * @param fencingToken {@link #tryClaim} 이 준 실행권 토큰
     * @return 기록됐으면 {@code true}. 실행권을 잃었으면 {@code false}
     * @throws TranscriptionChunkStoreUnavailableException 저장소에 쓸 수 없음
     */
    boolean markSkippedSilent(Long chunkId, int fencingToken, Instant now);

    /**
     * 재시도 가능한 실패를 기록한다. 단계는 {@code PENDING} 으로 돌리고 {@code next_attempt_at} 을 설정한다.
     *
     * <p>시도 횟수는 {@link #tryClaim} 에서 이미 올렸으므로 여기서 건드리지 않는다. 두 곳에서 올리면 한 번의 시도가 두 번으로 세어져 상한에 절반의 속도로 닿는다.
     *
     * @param fencingToken {@link #tryClaim} 이 준 실행권 토큰
     * @param error 실패 사유. <b>전사 원문·자격증명을 넣지 않는다</b>
     * @return 기록됐으면 {@code true}. 실행권을 잃었으면 {@code false}
     * @throws TranscriptionChunkStoreUnavailableException 저장소에 쓸 수 없음
     */
    boolean markRetry(Long chunkId, int fencingToken, String error, Instant nextAttemptAt, Instant now);

    /**
     * 재시도 불가 실패이거나 상한을 넘긴 것을 기록한다.
     *
     * @param fencingToken {@link #tryClaim} 이 준 실행권 토큰
     * @param error 실패 사유. <b>전사 원문·자격증명을 넣지 않는다</b>
     * @return 기록됐으면 {@code true}. 실행권을 잃었으면 {@code false}
     * @throws TranscriptionChunkStoreUnavailableException 저장소에 쓸 수 없음
     */
    boolean markFailed(Long chunkId, int fencingToken, String error, Instant now);

    /**
     * 세션의 모든 청크를 원본 파일·순번 순으로. 조립과 완료 판정에 쓴다.
     *
     * @throws TranscriptionChunkStoreUnavailableException 저장소를 읽을 수 없음
     */
    List<TranscriptionChunk> findAllBySessionId(Long sessionId);

    /**
     * 세션의 청크를 모두 지운다. 전사를 처음부터 다시 하는 경우에만 쓴다.
     *
     * @throws TranscriptionChunkStoreUnavailableException 저장소에 쓸 수 없음
     */
    void deleteBySessionId(Long sessionId);
}
