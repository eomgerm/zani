package com.a105.zani.session.application.checkjoinable;

import com.a105.zani.session.domain.model.SessionStatus;

/**
 * 들어갈 수 있는 수업의 최소 정보. 참가자 명단이나 강사 정보는 담지 않는다 — 아직 이 사용자는 참가자가 아니다.
 *
 * @param remainingSeats 남은 자리. 확인한 순간의 값이며 실제 입장까지 줄어들 수 있다
 */
public record CheckJoinableResult(Long sessionId, String inviteCode, SessionStatus status, int remainingSeats) {}
