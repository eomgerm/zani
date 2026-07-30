package com.a105.zani.session.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.a105.zani.session.domain.model.Session;

public interface SessionRepository {

    Session save(Session session);

    /** 아직 LIVE인데 시작 시각이 기준보다 이전인 세션(= 최대 수업 시간을 넘긴 세션). 오래된 순으로 최대 limit건. */
    List<Session> findLiveStartedBefore(Instant startedBefore, int limit);

    Optional<Session> findById(Long id);

    Optional<Session> findByInviteCode(String inviteCode);

    /**
     * 초대 코드로 세션을 찾되 행을 잠근다. 호출자의 트랜잭션이 끝날 때까지 같은 세션을 대상으로 한 다른 잠금 요청이 대기한다.
     *
     * <p>정원처럼 "세보고 나서 추가하는" 검사는 잠금 없이는 동시 요청에서 새어 나간다.
     */
    Optional<Session> findByInviteCodeForUpdate(String inviteCode);
}
