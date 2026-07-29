package com.a105.zani.session.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.a105.zani.session.domain.model.Session;

public interface SessionRepository {

    Session save(Session session);

    /** 아직 LIVE인데 시작 시각이 기준보다 이전인 세션(= 최대 수업 시간을 넘긴 세션). 오래된 순으로 최대 limit건. */
    List<Session> findLiveStartedBefore(Instant startedBefore, int limit);

    /** 아직 시작되지 않은 채 기준 시각 이전에 만들어진 세션. 강사가 방만 만들고 떠난 경우를 정리하는 데 쓴다. 오래된 순으로 최대 limit건. */
    List<Session> findPreparingCreatedBefore(Instant createdBefore, int limit);

    /** 메모 마감이 기준 시각 이전인 NOTE_PENDING 세션. 마감이 지난 세션을 최종 종료로 넘기는 데 쓴다. 오래된 순으로 최대 limit건. */
    List<Session> findNotePendingDueBefore(Instant dueBefore, int limit);

    Optional<Session> findById(Long id);

    Optional<Session> findByInviteCode(String inviteCode);

    /**
     * 초대 코드로 세션을 찾되 행을 잠근 채 반환한다. 정원 검사와 참가 관계 삽입 사이의 경쟁을 막는 유일한 수단이다.
     *
     * <p>가이드 §6은 동시 입장의 최종 방어를 LiveKit {@code maxParticipants=30}으로 두지만 Room 생성이 아직 없다. 그때까지는 같은 세션에 대한 입장을 이 잠금으로 직렬화해
     * 31번째를 확실히 막는다. 세션마다 별개의 행이라 다른 수업의 입장은 서로 기다리지 않는다.
     */
    Optional<Session> findByInviteCodeForUpdate(String inviteCode);
}
