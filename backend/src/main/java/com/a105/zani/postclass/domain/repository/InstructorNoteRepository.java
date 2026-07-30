package com.a105.zani.postclass.domain.repository;

import java.time.Instant;
import java.util.Optional;

import com.a105.zani.postclass.domain.model.InstructorNote;

public interface InstructorNoteRepository {

    Optional<InstructorNote> findBySessionId(Long sessionId);

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
}
