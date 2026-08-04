package com.a105.zani.postclass.application.port;

import java.time.Instant;
import java.util.List;
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
     * 세션의 작업 상태를 <b>다른 전이가 끼어들지 못하게 잡아 두고</b> 읽는다. 작업이 없으면 빈 값.
     *
     * <p>일반 조회가 아닌 이유: 단계를 옮기는 워커는 여럿이고 서로 다른 트랜잭션에서 같은 순간에 들어온다. 읽고 나서 쓰면 둘 다 같은 단계를 읽어 둘 다 옮기므로, 같은 단계가 두 번 수행되거나 한
     * 단계가 통째로 건너뛰어진다. 잠금 읽기는 뒤에 온 쪽을 앞선 전이가 커밋할 때까지 세워 두고, 그 다음 최신 커밋본을 보여 준다 — 뒤에 온 쪽이 자기 요청을 중복 보고로 알아볼 수 있는 것은 이
     * 때문이다.
     *
     * <p>뒤따르는 쓰기({@link #updateStatus}·{@link #markRetry}·{@link #markFailed})와 같은 트랜잭션에서 불러야 한다. 트랜잭션이 끊기면 잠금도 함께 풀려
     * 아무것도 막지 못한다.
     *
     * @throws PipelineJobUnavailableException 작업 저장소를 읽을 수 없음
     */
    Optional<PipelineJobState> findForUpdate(Long sessionId);

    /**
     * 세션의 작업 상태를 잠그지 않고 읽는다. 작업이 없으면 빈 값.
     *
     * <p>사후 전사 오케스트레이션이 {@code queuedAt} 을 얻는 데 쓴다 — 청크마다 재시도 여부를 정할 때 8시간 마감의 기준점이 필요하다. {@link #findForUpdate} 를 쓰면 안
     * 된다: 그 잠금은 전이 트랜잭션 안에서만 의미가 있고, 수십 분 걸리는 전사가 그것을 붙잡으면 SLA 경보와 다른 단계의 전이가 잠금 대기로 실패한다.
     *
     * <p>전이 판단에 쓰지 않는다. 잠금이 없으므로 읽은 값이 곧바로 낡을 수 있다.
     *
     * @throws PipelineJobUnavailableException 작업 저장소를 읽을 수 없음
     */
    Optional<PipelineJobState> find(Long sessionId);

    /**
     * 세션의 작업 단계를 바꾸고 <b>재시도 예산을 초기화한다</b>(시도 횟수 0, 대기 해제).
     *
     * <p>재시도 예산을 단계마다 새로 주기 위해서다. 초기화하지 않으면 앞 단계에서 쓴 시도 횟수가 남아, 다음 단계는 첫 실패에서 곧바로 상한에 걸린다.
     *
     * <p>실패 사유는 지우지 않는다 — {@link #markFailed} 와 이 호출의 순서에 따라 사유가 사라지지 않게 하기 위해서다.
     *
     * <p>조건 없이 덮어쓰므로, 갈 수 있는 단계인지는 {@link #findForUpdate} 로 잠근 뒤 호출자가 판단한다.
     *
     * @throws PipelineJobUnavailableException 작업 저장소에 쓸 수 없음
     */
    void updateStatus(Long sessionId, PipelineStatus status, Instant changedAt);

    /**
     * 현재 단계를 유지한 채 시도 횟수를 올리고 다음 시도 시각과 실패 사유를 남긴다.
     *
     * <p>단계를 되돌리지 않는 이유: 실패한 것은 현재 단계뿐이라 앞 단계까지 다시 돌 이유가 없고, 다시 돌면 8시간 예산만 줄어든다.
     *
     * @throws PipelineJobUnavailableException 작업 저장소에 쓸 수 없음
     */
    void markRetry(Long sessionId, String error, Instant nextAttemptAt, Instant changedAt);

    /**
     * 실패 사유를 남긴다. 단계를 FAILED 로 옮기는 것은 상태 머신을 거치는 별도 호출이다.
     *
     * @throws PipelineJobUnavailableException 작업 저장소에 쓸 수 없음
     */
    void markFailed(Long sessionId, String error, Instant changedAt);

    /**
     * 전사를 시작하거나 이어갈 수 있는 세션 ID. 오래 등록된 것부터 최대 limit 건.
     *
     * <p>두 경우만 담는다.
     *
     * <ul>
     *   <li>{@code QUEUED} — 아직 전사를 시작하지 않았다
     *   <li>{@code TRANSCRIBING} 이면서 {@code next_attempt_at} 이 {@code now} 이하 — 실패해 재시도 기한이 지났다
     * </ul>
     *
     * <p>{@code next_attempt_at} 이 {@code null} 인 {@code TRANSCRIBING} 은 <b>실행 중</b>이라 담지 않는다. 이 구분이 없으면 진행 중인 세션이 매
     * 주기마다 다시 발견되고, 같은 전사가 겹쳐 돌 수 있다.
     *
     * <p>이 조회는 선점이 아니다. 실제 시작은 잠금 읽기를 거치는 짧은 트랜잭션에서 따로 판정한다({@code TryStartTranscriptionUseCase}).
     *
     * @throws PipelineJobUnavailableException 작업 저장소를 읽을 수 없음
     */
    List<Long> findDueTranscriptionSessionIds(Instant now, int limit);

    /**
     * 프로세스가 죽어 남은 {@code TRANSCRIBING} 작업을 다시 발견되게 만든다. <b>시도 횟수는 올리지 않는다.</b>
     *
     * <p>{@link #findDueTranscriptionSessionIds} 는 {@code next_attempt_at} 이 {@code null} 인 {@code TRANSCRIBING} 을 "실행
     * 중" 으로 보고 제외한다. 그것이 실행 중인 세션을 매 주기마다 다시 집는 것을 막는 장치인데, 워커가 사라지면 같은 조건이 반대로 작동한다.
     *
     * <pre>
     * 서버 종료·크래시 → TRANSCRIBING + next_attempt_at = null 로 남음
     *   → 실제로 도는 워커는 없음
     *   → findDue 가 영원히 담지 않음 → 청크 lease 가 만료돼도 회수할 세션이 디스패치되지 않는다
     * </pre>
     *
     * <p>그래서 기동 시 한 번 대기 시각을 채워 재시도 대기 상태로 만든다. 그 뒤는 평소 경로와 같다 — {@code findDue} 가 담고
     * {@code TryStartTranscriptionUseCase} 가 대기를 풀고 이어간다. 성공한 청크는 체크포인트가 막아 다시 호출되지 않는다.
     *
     * <p><b>시도 횟수를 올리지 않는 이유.</b> 크래시는 GMS 실패가 아니다. 올리면 배포 한 번에 재시도 예산이 깎여, 실제로는 한 번도 실패하지 않은 세션이 상한에 걸린다. 무한 재기동이 예산을
     * 못 쓰는 문제는 8시간 마감이 대신 막는다.
     *
     * <p><b>기동 시점에만 부른다.</b> 그때는 이 인스턴스의 워커가 아직 없다. 주기적으로 부르면 살아서 도는 세션까지 재시도 대기로 만들어 같은 세션이 겹쳐 돈다(청크 fencing 이 결과를 지켜
     * 주긴 하지만 GMS 호출이 낭비된다).
     *
     * @return 되살린 작업 수
     * @throws PipelineJobUnavailableException 작업 저장소에 쓸 수 없음
     */
    int requeueStalledTranscriptions(Instant now);

    /**
     * 현재 단계를 유지한 채 재시도 대기만 푼다. <b>시도 횟수는 보존한다.</b>
     *
     * <p>재시도 선점 전용이다. {@link #updateStatus} 를 쓸 수 없는 이유는 그쪽이 시도 횟수를 0 으로 되돌리기 때문이다 — 그러면 실패를 반복하는 단계가 상한에 걸리지 않고 영원히
     * 재시도된다. {@code AdvancePipelineJobUseCase#advance} 도 쓸 수 없다. 이미 같은 단계면 아무것도 쓰지 않고 반환하므로 {@code next_attempt_at} 이
     * 남아, 실행 중에도 매 주기마다 다시 발견된다.
     *
     * @throws PipelineJobUnavailableException 작업 저장소에 쓸 수 없음
     */
    void clearRetryWait(Long sessionId, Instant changedAt);

    /**
     * 아직 끝나지 않았는데 마감을 넘긴 작업의 세션 ID. 오래 밀린 것부터 최대 limit 건.
     *
     * <p>세션 ID 만 읽는 이유: 경보에 필요한 것은 대상 식별과 건수뿐이다.
     *
     * @param queuedBefore 이 시각 이전에 등록된 작업이 마감을 넘긴 것이다(= now - 8시간)
     * @throws PipelineJobUnavailableException 작업 저장소를 읽을 수 없음
     */
    List<Long> findOverdueSessionIds(Instant queuedBefore, int limit);
}
