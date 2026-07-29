package com.a105.zani.session.domain.model;

import java.time.Instant;

/**
 * 세션 상태 전이 이력 한 건. 세션의 현재 상태만으로는 "언제 어떤 경로로 여기까지 왔는지"를 알 수 없어, 생명주기 문제를 사후에 추적하려면 전이를 남겨야 한다.
 *
 * @param fromStatus 전이 전 상태. 세션이 처음 만들어지는 전이에서는 null이다.
 */
public record SessionStatusChange(
        Long id, Long sessionId, SessionStatus fromStatus, SessionStatus toStatus, Instant changedAt) {}
