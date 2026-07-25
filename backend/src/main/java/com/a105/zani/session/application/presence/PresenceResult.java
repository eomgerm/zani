package com.a105.zani.session.application.presence;

import java.time.Instant;

import com.a105.zani.session.domain.model.ConnectionState;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/** presence heartbeat 처리 결과. 참가자 상태와 재연결·세션 종료 신호를 담는다. */
public record PresenceResult(
        Long participantId,
        SessionParticipantRole role,
        ConnectionState connectionState,
        Instant heartbeatAt,
        ReconnectStatus reconnectStatus,
        boolean sessionEnded) {}
