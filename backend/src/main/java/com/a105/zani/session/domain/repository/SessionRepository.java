package com.a105.zani.session.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.a105.zani.session.domain.model.Session;

public interface SessionRepository {

    Session save(Session session);

    /** 아직 LIVE인데 시작 시각이 기준보다 이전인 세션(= 최대 수업 시간을 넘긴 세션). 오래된 순으로 최대 limit건. */
    List<Session> findLiveStartedBefore(Instant startedBefore, int limit);

    /**
     * 아직 PREPARING인데 만들어진 지 기준보다 오래된 세션(= 시작하지 않고 방치된 세션). 오래된 순으로 최대 limit건.
     *
     * <p>시작 시각이 아니라 생성 시각으로 찾는다. 준비 중인 세션에는 시작 시각이 없어 최대 수업 시간 기준으로는 걸리지 않는다.
     */
    List<Session> findPreparingCreatedBefore(Instant createdBefore, int limit);

    Optional<Session> findById(Long id);

    /**
     * 세션을 찾되 행을 잠근다. 호출자의 트랜잭션이 끝날 때까지 같은 세션을 대상으로 한 다른 잠금 요청이 대기한다.
     *
     * <p>상태 전이 경로(시작·종료)가 쓴다. 잠금 없이 읽으면 두 요청이 같은 상태를 읽고 각자 전이해, 늦게 커밋한 쪽이 앞선 전이를 덮어쓴다 — 종료된 세션이 다시 LIVE 로 되살아날 수 있다.
     */
    Optional<Session> findByIdForUpdate(Long id);

    Optional<Session> findByInviteCode(String inviteCode);

    /**
     * 초대 코드로 세션을 찾되 행을 잠근다. 호출자의 트랜잭션이 끝날 때까지 같은 세션을 대상으로 한 다른 잠금 요청이 대기한다.
     *
     * <p>정원처럼 "세보고 나서 추가하는" 검사는 잠금 없이는 동시 요청에서 새어 나간다.
     */
    Optional<Session> findByInviteCodeForUpdate(String inviteCode);
}
