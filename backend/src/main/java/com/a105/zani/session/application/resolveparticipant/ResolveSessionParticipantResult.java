package com.a105.zani.session.application.resolveparticipant;

import java.time.Instant;

import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * 확인된 세션 참가자. 다른 도메인은 session 엔티티가 아니라 이 값만 받는다.
 *
 * @param sessionStartedAt 세션 시작 시각. 수업 안의 상대 시각(offset)을 계산하는 기준이다.
 * @param sessionExpiresAt 세션이 자동 종료되는 시각. 수업이 끝나면 의미가 없어지는 값의 보관 기간을 여기에 맞춘다.
 */
public record ResolveSessionParticipantResult(
        Long participantId, SessionParticipantRole role, Instant sessionStartedAt, Instant sessionExpiresAt) {}
