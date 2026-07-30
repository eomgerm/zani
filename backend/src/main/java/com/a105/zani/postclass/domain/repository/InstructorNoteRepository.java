package com.a105.zani.postclass.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.a105.zani.postclass.domain.model.InstructorNote;

public interface InstructorNoteRepository {

    Optional<InstructorNote> findBySessionId(Long sessionId);

    /**
     * 마지막 입력이 기준 시각보다 이전인 초안이 달린 세션 ID. 오래 방치된 것부터 최대 limit 건.
     *
     * <p>메모 전체가 아니라 세션 ID만 읽는 이유: 자동 확정에 필요한 것은 대상 식별뿐인데, 메모마다 본문까지 실어 오면 한 번의 스윕이 쓰지도 않는 5000자를 건수만큼 끌어온다.
     */
    List<Long> findDueDraftSessionIds(Instant editedBefore, int limit);

    /**
     * 메모를 저장한다. 아직 없으면 만들고, 있으면 본문과 마지막 입력 시각만 덮어쓴다.
     *
     * @throws com.a105.zani.postclass.domain.exception.NoteAlreadyFinalizedException 읽어 둔 사이에 확정되어 더는 수정할 수 없음
     * @throws com.a105.zani.postclass.domain.exception.ConcurrentNoteOpenException 다른 요청이 같은 세션의 메모를 먼저 열었음
     */
    InstructorNote save(InstructorNote instructorNote);

    /**
     * 세션의 메모를 DRAFT → FINALIZED 로 원자 전환한다. 전이가 실제로 일어났을 때만 {@code true}.
     *
     * <p>수동 완료와 30분 비활성 확정(티켓 90)은 서로 다른 트랜잭션에서 동시에 들어올 수 있다. 읽고 나서 쓰는 방식은 둘 다 DRAFT 를 읽어 둘 다 확정하므로, 조건부 UPDATE 한 문장으로
     * 승자를 하나만 남긴다 — recording_outbox 의 claim(PENDING→IN_PROGRESS)과 같은 이유, 같은 방식이다. 확정 후속 작업(NOTE-004의 사후 처리 job 생성)은
     * {@code true} 를 받은 호출자만 수행한다.
     *
     * <p>메모 ID 가 아니라 세션 ID 로 지목하는 이유: 메모는 세션당 한 행이고 후속 작업도 세션 단위라, 두 확정 경로 모두 세션 ID 만으로 끝난다.
     */
    boolean finalizeIfDraft(Long sessionId, Instant finalizedAt);

    /**
     * 여전히 방치 상태인 초안만 FINALIZED 로 원자 전환한다. 전이가 실제로 일어났을 때만 {@code true}.
     *
     * <p>{@link #finalizeIfDraft} 와 달리 마지막 입력 시각까지 다시 검사한다. 스윕이 대상을 고른 뒤 확정하기 전에 강사가 다시 입력할 수 있는데, 상태만 보면 그 메모가 방금
     * 살아났는데도 확정된다 — 입력이 타이머를 초기화한다는 규칙(NOTE-002)이 깨진다.
     */
    boolean finalizeIfStillInactive(Long sessionId, Instant editedBefore, Instant finalizedAt);

    /**
     * 초안 없이 확정된 메모를 만든다. 같은 세션의 행이 이미 있으면 아무것도 하지 않는다.
     *
     * <p>유니크 제약 위반을 예외로 받지 않는 이유: 확정은 멱등이어야 한다(FRD §16). 위반이 한 번 나면 그 트랜잭션은 롤백 대상이 되어, 예외를 잡아 다시 읽어도 커밋할 수 없다. 그래서 애초에
     * 위반이 나지 않는 방식으로 넣는다.
     *
     * @return 이번 호출로 만들었으면 그 메모 ID, 이미 있었으면 빈 값
     */
    Optional<Long> insertFinalizedIfAbsent(Long sessionId, Long instructorParticipantId, Instant finalizedAt);

    /** <b>이미 커밋된</b> 메모 ID. {@link #findCommittedFinalizedAt} 와 같은 이유로 스냅숏 밖을 본다. */
    Optional<Long> findCommittedNoteId(Long sessionId);

    /**
     * <b>이미 커밋된</b> 확정 시각. 아직 확정되지 않았으면 빈 값.
     *
     * <p>{@link #findBySessionId} 로는 이 값을 얻을 수 없다. 같은 트랜잭션의 일반 조회는 처음 읽은 시점의 스냅숏을 계속 쓰기 때문에, 그 사이 다른 요청이 커밋한 확정이 보이지
     * 않는다. 확정 경합에서 진 경로가 실제 확정 시각을 응답하려면 스냅숏 밖을 봐야 한다.
     */
    Optional<Instant> findCommittedFinalizedAt(Long sessionId);
}
