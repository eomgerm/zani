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
}
